package net.minecraft.network.protocol.game;
import net.minecraft.world.phys.BlockHitResult;
public class ServerboundUseItemOnPacket { public BlockHitResult hit = new BlockHitResult(); public int sequence; public BlockHitResult getHitResult(){return hit;} public int getSequence(){return sequence;} }
