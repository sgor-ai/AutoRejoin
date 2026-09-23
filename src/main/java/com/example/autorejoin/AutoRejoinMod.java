package com.example.autorejoin;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.toasts.SystemToast;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.gui.screens.DisconnectedScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.multiplayer.JoinMultiplayerScreen;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

public class AutoRejoinMod implements ClientModInitializer {

    private static final ScheduledExecutorService SCHEDULER = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "autorejoin-pinger");
        t.setDaemon(true);
        return t;
    });

    private static ScheduledFuture<?> activeTask = null;
    private static volatile boolean watching = false;

    private static volatile String lastHost = null;
    private static volatile int lastPort = 25565;
    private static volatile long lastIntervalMs = 500L; // Default: 0.5 secondi

    private static volatile boolean autoConnectAttemptInProgress = false;
    private static volatile long lastConnectAttemptMillis = 0L;
    private static final long CONNECT_COOLDOWN_MS = 3500L;

    @Override
    public void onInitializeClient() {
        registerCommand();
        registerConnectionEvents();
        registerFailsafeWatchdog();
    }

    private void registerCommand() {
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> {
            dispatcher.register(ClientCommands.literal("autorejoin")
                .then(ClientCommands.literal("start")
                    .then(ClientCommands.argument("indirizzo", StringArgumentType.word())
                        .then(ClientCommands.argument("porta", IntegerArgumentType.integer(1, 65535))
                            .then(ClientCommands.argument("intervalloSecondi", IntegerArgumentType.integer(1, 300))
                                .executes(ctx -> {
                                    String host = StringArgumentType.getString(ctx, "indirizzo");
                                    int port = IntegerArgumentType.getInteger(ctx, "porta");
                                    int intervalSec = IntegerArgumentType.getInteger(ctx, "intervalloSecondi");
                                    checkAndStartWatching(host, port, intervalSec * 1000L);
                                    return 1;
                                })
                            )
                            .executes(ctx -> {
                                String host = StringArgumentType.getString(ctx, "indirizzo");
                                int port = IntegerArgumentType.getInteger(ctx, "porta");
                                checkAndStartWatching(host, port, 500L); // Default 0.5s
                                return 1;
                            })
                        )
                        .executes(ctx -> {
                            String host = StringArgumentType.getString(ctx, "indirizzo");
                            checkAndStartWatching(host, 25565, 500L); // Default 0.5s
                            return 1;
                        })
                    )
                )
                .then(ClientCommands.literal("stop")
                    .executes(ctx -> {
                        if (isWatching()) {
                            stopWatching();
                            feedback("§eAuto-rejoin fermato.");
                        } else {
                            feedback("§cAuto-rejoin non è attualmente in esecuzione.");
                        }
                        return 1;
                    })
                )
            );
        });
    }

    private void registerConnectionEvents() {
        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> {
            if (autoConnectAttemptInProgress || watching) {
                autoConnectAttemptInProgress = false;
                stopWatching();
                feedback("§aConnesso! Sei entrato nel server. Auto-rejoin fermato.");
            }
        });

        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
            if (autoConnectAttemptInProgress) {
                onFailedAttempt();
            }
        });
    }

    private void registerFailsafeWatchdog() {
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (!autoConnectAttemptInProgress) return;
            if (client.player != null) return;

            Screen currentScreen = getCurrentScreen(client);
            if (currentScreen instanceof DisconnectedScreen) {
                onFailedAttempt();
            }
        });
    }

    private static Screen getCurrentScreen(Minecraft client) {
        if (client == null) return null;
        try {
            for (java.lang.reflect.Field f : Minecraft.class.getDeclaredFields()) {
                if (Screen.class.isAssignableFrom(f.getType())) {
                    f.setAccessible(true);
                    Object val = f.get(client);
                    if (val instanceof Screen) {
                        return (Screen) val;
                    }
                }
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    private static void onFailedAttempt() {
        autoConnectAttemptInProgress = false;
        feedback("§ePosto occupato durante l'accesso o connessione rifiutata. Pausa di 5 secondi...");
        if (lastHost != null && watching) {
            scheduleNextCheck(lastHost, lastPort, lastIntervalMs, 5000L);
        }
    }

    public static void startWatchingFromGui(String rawAddress) {
        String host = rawAddress;
        int port = 25565;
        if (rawAddress.contains(":")) {
            String[] parts = rawAddress.split(":", 2);
            host = parts[0];
            try {
                port = Integer.parseInt(parts[1]);
            } catch (NumberFormatException ignored) {
            }
        }
        checkAndStartWatching(host, port, 500L); // 0.5s
    }

    private static boolean isAlreadyConnectedTo(String host, int port) {
        Minecraft client = Minecraft.getInstance();
        if (client.player == null) return false;

        try {
            for (java.lang.reflect.Method m : Minecraft.class.getDeclaredMethods()) {
                if (ServerData.class.isAssignableFrom(m.getReturnType()) && m.getParameterCount() == 0) {
                    m.setAccessible(true);
                    ServerData currentServer = (ServerData) m.invoke(client);
                    if (currentServer != null && currentServer.ip != null) {
                        String currentIp = currentServer.ip.toLowerCase().trim();
                        String targetIp = host.toLowerCase().trim();
                        if (currentIp.equals(targetIp) || currentIp.startsWith(targetIp + ":")) {
                            return true;
                        }
                    }
                }
            }
        } catch (Exception ignored) {
        }
        return false;
    }

    public static void checkAndStartWatching(String host, int port, long intervalMs) {
        if (isAlreadyConnectedTo(host, port)) {
            feedback("§cSei già connesso a questo server! L'auto-rejoin non è stato avviato.");
            return;
        }
        startWatching(host, port, intervalMs);
    }

    public static void startWatching(String host, int port, long intervalMs) {
        stopWatching();

        lastHost = host;
        lastPort = port;
        lastIntervalMs = intervalMs;

        watching = true;
        double sec = intervalMs / 1000.0;
        feedback("§aAuto-rejoin avviato su " + host + ":" + port
            + " (controllo ogni " + sec + "s). Usa /autorejoin stop o il pulsante nel menu per fermare.");

        scheduleNextCheck(host, port, intervalMs, 0L);
    }

    private static synchronized void scheduleNextCheck(String host, int port, long intervalMs, long delayMs) {
        if (activeTask != null && !activeTask.isDone()) {
            activeTask.cancel(false);
        }

        activeTask = SCHEDULER.schedule(() -> {
            if (!watching) return;

            long now = System.currentTimeMillis();
            long elapsed = now - lastConnectAttemptMillis;

            if (autoConnectAttemptInProgress && elapsed < CONNECT_COOLDOWN_MS) {
                scheduleNextCheck(host, port, intervalMs, 1000L);
                return;
            }

            try {
                ServerPinger.PingResult result = ServerPinger.ping(host, port, -1, 2000);

                if (result.hasFreeSlot()) {
                    if (elapsed < CONNECT_COOLDOWN_MS) {
                        scheduleNextCheck(host, port, intervalMs, 1000L);
                        return;
                    }

                    feedback("§aPosto libero rilevato (" + result.online + "/" + result.max
                        + ")! Connessione in corso...");

                    Minecraft client = Minecraft.getInstance();
                    lastConnectAttemptMillis = System.currentTimeMillis();
                    autoConnectAttemptInProgress = true;
                    client.execute(() -> connectNow(client, host, port));
                    playFoundSound();

                    scheduleNextCheck(host, port, intervalMs, 5000L);
                } else {
                    autoConnectAttemptInProgress = false;
                    scheduleNextCheck(host, port, intervalMs, intervalMs);
                }
            } catch (Exception e) {
                autoConnectAttemptInProgress = false;
                scheduleNextCheck(host, port, intervalMs, intervalMs);
            }
        }, delayMs, TimeUnit.MILLISECONDS);
    }

    public static synchronized void stopWatching() {
        watching = false;
        autoConnectAttemptInProgress = false;
        if (activeTask != null) {
            activeTask.cancel(false);
            activeTask = null;
        }
    }

    public static boolean isWatching() {
        return watching;
    }

    private static void connectNow(Minecraft client, String host, int port) {
        String address = port == 25565 ? host : host + ":" + port;
        ServerData serverData = new ServerData("Auto Rejoin", address, ServerData.Type.OTHER);

        ConnectScreen.startConnecting(
            new JoinMultiplayerScreen(null),
            client,
            ServerAddress.parseString(address),
            serverData,
            false,
            null
        );
    }

    private static void playFoundSound() {
        Minecraft client = Minecraft.getInstance();
        client.execute(() -> {
            if (client.getSoundManager() != null) {
                client.getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.PLAYER_LEVELUP, 1.0F));
            }
        });
    }

    private static void feedback(String message) {
        Minecraft client = Minecraft.getInstance();
        client.execute(() -> {
            if (client.player != null) {
                client.player.sendSystemMessage(Component.literal("[AutoRejoin] " + message));
            } else if (client.gui != null) {
                client.gui.toastManager().addToast(
                    new SystemToast(SystemToast.SystemToastId.PERIODIC_NOTIFICATION,
                        Component.literal("Auto Rejoin"), Component.literal(message))
                );
            }
            System.out.println("[AutoRejoin] " + message);
        });
    }
}