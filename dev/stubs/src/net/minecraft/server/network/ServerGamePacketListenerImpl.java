package net.minecraft.server.network;
import java.util.*;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.*;
import net.minecraft.server.level.ServerPlayer;
public class ServerGamePacketListenerImpl extends ServerCommonPacketListenerImpl { public ServerPlayer player; public List<String> disconnects = new ArrayList<>(); public List<Object> sent = new ArrayList<>(); public int lastAck=-1;
 public void ackBlockChangesUpTo(int s){lastAck=s;} public void send(Object o){sent.add(o);} public void disconnect(Component c){disconnects.add(c.getString());}
 public void handleContainerClick(ServerboundContainerClickPacket p){} public void handleSetCreativeModeSlot(ServerboundSetCreativeModeSlotPacket p){} public void handleUseItemOn(ServerboundUseItemOnPacket p){} public void handlePlayerAction(ServerboundPlayerActionPacket p){} }
