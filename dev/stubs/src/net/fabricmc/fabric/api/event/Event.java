package net.fabricmc.fabric.api.event;
public class Event<T> { public T listener; public void register(T t){listener=t;} }
