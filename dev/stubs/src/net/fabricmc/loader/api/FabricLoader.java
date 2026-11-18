package net.fabricmc.loader.api;
import java.nio.file.*;
public class FabricLoader { private static final FabricLoader I = new FabricLoader(); public static FabricLoader getInstance(){return I;} public Path getConfigDir(){return Paths.get("config");} }
