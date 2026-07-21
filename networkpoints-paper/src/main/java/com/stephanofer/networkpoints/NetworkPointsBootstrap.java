package com.stephanofer.networkpoints;

import io.papermc.paper.plugin.bootstrap.BootstrapContext;
import io.papermc.paper.plugin.bootstrap.PluginProviderContext;
import io.papermc.paper.plugin.bootstrap.PluginBootstrap;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import org.incendo.cloud.execution.ExecutionCoordinator;
import org.incendo.cloud.paper.PaperCommandManager;
import org.incendo.cloud.setting.ManagerSetting;

/** Paper bootstrap entry point for the Cloud command manager. */
public final class NetworkPointsBootstrap implements PluginBootstrap {

    private PaperCommandManager.Bootstrapped<CommandSourceStack> commandManager;

    @Override
    public void bootstrap(BootstrapContext context) {
        this.commandManager = PaperCommandManager.builder()
                .executionCoordinator(ExecutionCoordinator.simpleCoordinator())
                .buildBootstrapped(context);
        this.commandManager.settings().set(ManagerSetting.ALLOW_UNSAFE_REGISTRATION, true);
    }

    @Override
    public NetworkPointsPlugin createPlugin(PluginProviderContext context) {
        if (this.commandManager == null) {
            throw new IllegalStateException("Cloud command manager was not bootstrapped");
        }
        return new NetworkPointsPlugin(this.commandManager);
    }
}
