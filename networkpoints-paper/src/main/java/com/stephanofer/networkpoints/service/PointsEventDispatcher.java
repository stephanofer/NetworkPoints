package com.stephanofer.networkpoints.service;

import org.bukkit.event.Event;

@FunctionalInterface
public interface PointsEventDispatcher {
    void dispatch(Event event);
}
