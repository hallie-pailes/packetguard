package net.minecraft.network.protocol.game;
import net.minecraft.core.BlockPos;
public class ServerboundPlayerActionPacket { public enum Action { START_DESTROY_BLOCK, ABORT_DESTROY_BLOCK, STOP_DESTROY_BLOCK, DROP_ALL_ITEMS, DROP_ITEM, RELEASE_USE_ITEM, SWAP_ITEM_WITH_OFFHAND } public Action action; public BlockPos pos; public int sequence; public Action getAction(){return action;} public BlockPos getPos(){return pos;} public int getSequence(){return sequence;} }
