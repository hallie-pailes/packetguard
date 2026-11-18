package net.minecraft.server;
import java.util.*;
public class MinecraftServer { public int tick; public boolean sameThread = true; public List<Runnable> queued = new ArrayList<>();
 public int getTickCount(){return tick;} public boolean isSameThread(){return sameThread;} public void execute(Runnable r){queued.add(r);} public void runQueued(){List<Runnable> q=new ArrayList<>(queued);queued.clear();for(Runnable r:q)r.run();} }
