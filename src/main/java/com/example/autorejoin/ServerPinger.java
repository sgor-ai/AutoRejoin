package com.example.autorejoin;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

public class ServerPinger {

    public static class PingResult {
        public final int online;
        public final int max;

        public PingResult(int online, int max) {
            this.online = online;
            this.max = max;
        }

        public boolean hasFreeSlot() {
            return online < max;
        }
    }

    public static PingResult ping(String host, int port, int protocolVersion, int timeoutMs) throws IOException {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(host, port), timeoutMs);
            socket.setSoTimeout(timeoutMs);

            DataOutputStream out = new DataOutputStream(socket.getOutputStream());
            DataInputStream in = new DataInputStream(socket.getInputStream());

            ByteArrayOutputStream handshake = new ByteArrayOutputStream();
            DataOutputStream hs = new DataOutputStream(handshake);
            writeVarInt(hs, 0x00);
            writeVarInt(hs, protocolVersion);
            writeString(hs, host);
            hs.writeShort(port);
            writeVarInt(hs, 1);
            writePacket(out, handshake.toByteArray());

            ByteArrayOutputStream statusReq = new ByteArrayOutputStream();
            DataOutputStream sr = new DataOutputStream(statusReq);
            writeVarInt(sr, 0x00);
            writePacket(out, statusReq.toByteArray());

            readVarInt(in);
            int packetId = readVarInt(in);
            if (packetId != 0x00) {
                throw new IOException("Pacchetto di risposta inatteso: " + packetId);
            }
            String json = readString(in);

            return parsePlayers(json);
        }
    }

    private static PingResult parsePlayers(String json) throws IOException {
        int playersIdx = json.indexOf("\"players\":{");
        if (playersIdx == -1) {
            playersIdx = json.indexOf("\"players\": {");
        }
        if (playersIdx == -1) {
            playersIdx = json.indexOf("\"players\":");
        }
        if (playersIdx == -1) throw new IOException("JSON di stato senza campo players: " + json);

        int max = extractInt(json, "\"max\"", playersIdx);
        int online = extractInt(json, "\"online\"", playersIdx);
        return new PingResult(online, max);
    }

    private static int extractInt(String json, String key, int fromIndex) throws IOException {
        int idx = json.indexOf(key, fromIndex);
        if (idx == -1) throw new IOException("Campo mancante: " + key);
        int colon = json.indexOf(':', idx);
        int i = colon + 1;
        while (i < json.length() && Character.isWhitespace(json.charAt(i))) {
            i++;
        }
        StringBuilder num = new StringBuilder();
        while (i < json.length() && (Character.isDigit(json.charAt(i)) || json.charAt(i) == '-')) {
            num.append(json.charAt(i));
            i++;
        }
        if (num.length() == 0) throw new IOException("Impossibile leggere il valore numerico per: " + key);
        return Integer.parseInt(num.toString());
    }

    private static void writePacket(DataOutputStream out, byte[] data) throws IOException {
        writeVarInt(out, data.length);
        out.write(data);
        out.flush();
    }

    private static void writeVarInt(DataOutputStream out, int value) throws IOException {
        while (true) {
            if ((value & ~0x7F) == 0) {
                out.writeByte(value);
                return;
            }
            out.writeByte((value & 0x7F) | 0x80);
            value >>>= 7;
        }
    }

    private static int readVarInt(DataInputStream in) throws IOException {
        int numRead = 0;
        int result = 0;
        byte read;
        do {
            read = in.readByte();
            int value = read & 0x7F;
            result |= (value << (7 * numRead));
            numRead++;
            if (numRead > 5) throw new IOException("VarInt troppo lungo");
        } while ((read & 0x80) != 0);
        return result;
    }

    private static void writeString(DataOutputStream out, String s) throws IOException {
        byte[] bytes = s.getBytes(StandardCharsets.UTF_8);
        writeVarInt(out, bytes.length);
        out.write(bytes);
    }

    private static String readString(DataInputStream in) throws IOException {
        int length = readVarInt(in);
        byte[] bytes = new byte[length];
        in.readFully(bytes);
        return new String(bytes, StandardCharsets.UTF_8);
    }
}