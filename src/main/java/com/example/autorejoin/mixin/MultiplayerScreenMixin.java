package com.example.autorejoin.mixin;

import com.example.autorejoin.AutoRejoinMod;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.multiplayer.JoinMultiplayerScreen;
import net.minecraft.client.gui.screens.multiplayer.ServerSelectionList;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(JoinMultiplayerScreen.class)
public abstract class MultiplayerScreenMixin extends Screen {

    protected MultiplayerScreenMixin(Component title) {
        super(title);
    }

    @Shadow
    private ServerSelectionList serverSelectionList;

    @Unique
    private Button autorejoin$toggleButton;

    @Inject(method = "init", at = @At("TAIL"))
    private void autorejoin$addForceEnterButton(CallbackInfo ci) {
        int buttonWidth = 100;
        int buttonHeight = 20;

        int xPos = Math.max(5, this.width - buttonWidth - 5);
        int yPos = 5;

        autorejoin$toggleButton = Button.builder(
            getButtonText(),
            button -> {
                if (AutoRejoinMod.isWatching()) {
                    AutoRejoinMod.stopWatching();
                } else {
                    if (this.serverSelectionList != null) {
                        Object selected = this.serverSelectionList.getSelected();
                        ServerData data = extractServerData(selected);
                        if (data != null) {
                            AutoRejoinMod.startWatchingFromGui(data.ip);
                        }
                    }
                }
                button.setMessage(getButtonText());
            }
        ).bounds(xPos, yPos, buttonWidth, buttonHeight).build();

        this.addRenderableWidget(autorejoin$toggleButton);
    }

    @Inject(method = "tick", at = @At("HEAD"))
    private void autorejoin$updateButtonState(CallbackInfo ci) {
        if (autorejoin$toggleButton != null) {
            // Aggiorna dinamicamente la posizione in alto a destra in caso di ridimensionamento della finestra o F11
            int buttonWidth = autorejoin$toggleButton.getWidth();
            int xPos = Math.max(5, this.width - buttonWidth - 5);
            autorejoin$toggleButton.setX(xPos);
            autorejoin$toggleButton.setY(5);

            // Sincronizza lo stato del testo
            autorejoin$toggleButton.setMessage(getButtonText());
        }
    }

    @Unique
    private Component getButtonText() {
        return Component.literal(AutoRejoinMod.isWatching() ? "Stop Entering" : "Force Enter");
    }

    @Unique
    private static ServerData extractServerData(Object entry) {
        if (entry == null) return null;
        try {
            for (java.lang.reflect.Field f : entry.getClass().getDeclaredFields()) {
                if (ServerData.class.isAssignableFrom(f.getType())) {
                    f.setAccessible(true);
                    return (ServerData) f.get(entry);
                }
            }
        } catch (Exception ignored) {
        }
        return null;
    }
}