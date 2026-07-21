package com.stephanofer.networkpoints;

import io.papermc.paper.plugin.loader.PluginClasspathBuilder;
import io.papermc.paper.plugin.loader.PluginLoader;

/**
 * Paper classpath entry point. Private libraries are currently bundled by Shadow.
 */
public final class NetworkPointsLoader implements PluginLoader {

    @Override
    public void classloader(PluginClasspathBuilder classpathBuilder) {
        // No dynamic libraries are required.
    }
}
