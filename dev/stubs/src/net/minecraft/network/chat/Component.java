package net.minecraft.network.chat;
public class Component { private final String s; private Component(String s){this.s=s;} public static Component literal(String s){return new Component(s);} public String getString(){return s;} }
