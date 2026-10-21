package gale.meowtils.api.handler.command;

import net.minecraft.command.CommandException;

import java.util.Collections;
import java.util.List;

/** Meowtils: a chat command ("/name args"). */
public abstract class ClientCommand {
    public abstract String getName();

    public abstract void process(String[] args) throws CommandException;

    public List<String> getAliases() { return Collections.emptyList(); }

    public boolean tabCompleteNames() { return false; }
}
