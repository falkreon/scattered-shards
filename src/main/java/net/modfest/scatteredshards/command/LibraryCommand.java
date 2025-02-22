package net.modfest.scatteredshards.command;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.tree.CommandNode;
import me.lucko.fabric.api.permissions.v0.Permissions;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import net.modfest.scatteredshards.ScatteredShards;
import net.modfest.scatteredshards.api.ScatteredShardsAPI;
import net.modfest.scatteredshards.api.impl.ShardLibraryPersistentState;
import net.modfest.scatteredshards.api.shard.Shard;
import net.modfest.scatteredshards.api.shard.ShardType;
import net.modfest.scatteredshards.networking.S2CSyncLibrary;
import net.modfest.scatteredshards.networking.S2CSyncShard;
import net.modfest.scatteredshards.networking.S2CUpdateShard;

import java.util.Optional;

public class LibraryCommand {

	public static int delete(CommandContext<ServerCommandSource> ctx) throws CommandSyntaxException {
		Identifier shardId = ctx.getArgument("shard_id", Identifier.class);

		var library = ScatteredShardsAPI.getServerLibrary();
		library.shards().get(shardId).orElseThrow(() -> ShardCommand.INVALID_SHARD.create(shardId));

		Optional<Shard> shard = library.shards().get(shardId);
		library.shards().remove(shardId);
		shard.ifPresent(it -> library.shardSets().remove(it.sourceId(), shardId));
		var server = ctx.getSource().getServer();
		ShardLibraryPersistentState.get(server).markDirty();
		var deletePacket = new S2CUpdateShard(shardId, S2CUpdateShard.Mode.DELETE);
		for (var player : server.getPlayerManager().getPlayerList()) {
			ServerPlayNetworking.send(player, deletePacket);
		}

		ctx.getSource().sendFeedback(() -> Text.stringifiedTranslatable("commands.scattered_shards.shard.library.delete", shardId), true);

		return Command.SINGLE_SUCCESS;
	}

	public static int deleteAll(CommandContext<ServerCommandSource> ctx) {
		var library = ScatteredShardsAPI.getServerLibrary();
		int toDelete = library.shards().size();
		library.shards().clear();
		library.shardSets().clear();
		var server = ctx.getSource().getServer();
		ShardLibraryPersistentState.get(server).markDirty();
		var syncLibrary = new S2CSyncLibrary(library);
		for (var player : server.getPlayerManager().getPlayerList()) {
			ServerPlayNetworking.send(player, syncLibrary);
		}

		ctx.getSource().sendFeedback(() -> Text.stringifiedTranslatable("commands.scattered_shards.shard.library.delete.all", toDelete), true);

		return toDelete;
	}

	public static int migrate(CommandContext<ServerCommandSource> ctx) throws CommandSyntaxException {
		Identifier shardId = ctx.getArgument("shard_id", Identifier.class);
		String modId = StringArgumentType.getString(ctx, "mod_id");
		Identifier shardTypeId = ctx.getArgument("shard_type", Identifier.class);
		Identifier newShardId = ShardType.createModId(shardTypeId, modId);

		var library = ScatteredShardsAPI.getServerLibrary();
		library.shardTypes().get(shardTypeId).orElseThrow(() -> ShardCommand.INVALID_SHARD_TYPE.create(shardTypeId));
		Shard shard = library.shards().get(shardId).orElseThrow(() -> ShardCommand.INVALID_SHARD.create(shardId));

		library.shards().remove(shardId);
		library.shardSets().values().removeIf(i -> i.equals(shardId));
		shard.setShardType(shardTypeId);
		library.shards().put(newShardId, shard);

		var server = ctx.getSource().getServer();
		ShardLibraryPersistentState.get(server).markDirty();

		var deleteShard = new S2CUpdateShard(shardId, S2CUpdateShard.Mode.DELETE);
		var syncShard = new S2CSyncShard(newShardId, shard);
		for (var player : server.getPlayerManager().getPlayerList()) {
			ServerPlayNetworking.send(player, deleteShard);
			ServerPlayNetworking.send(player, syncShard);
		}

		ctx.getSource().sendFeedback(() -> Text.stringifiedTranslatable("commands.scattered_shards.shard.library.migrate", shardId, newShardId), true);

		return Command.SINGLE_SUCCESS;
	}

	public static void register(CommandNode<ServerCommandSource> parent) {
		var library = Node.literal("library")
			.requires(
				Permissions.require(ScatteredShards.permission("command.library"), 3)
			)
			.build();

		//Usage: /shard library delete <shard_id>
		var deleteCommand = Node.literal("delete")
			.requires(
				Permissions.require(ScatteredShards.permission("command.library.delete"), 3)
			)
			.build();
		var deleteIdArgument = Node.shardId("shard_id")
			.executes(LibraryCommand::delete)
			.build();
		deleteCommand.addChild(deleteIdArgument);
		library.addChild(deleteCommand);

		//Usage: /shard library delete all
		var deleteAllCommand = Node.literal("all")
			.requires(
				Permissions.require(ScatteredShards.permission("command.library.delete.all"), 4)
			)
			.executes(LibraryCommand::deleteAll)
			.build();
		deleteCommand.addChild(deleteAllCommand);

		var migrateCommand = Node.literal("migrate")
			.requires(
				Permissions.require(ScatteredShards.permission("command.library.migrate"), 3)
			)
			.build();
		var migrateShardArg = Node.shardId("shard_id").build();
		var migrateModArg = Node.stringArgument("mod_id").suggests(Node::suggestModIds).build();
		var migrateShardTypeArg = Node.identifier("shard_type").suggests(Node::suggestShardTypes)
			.executes(LibraryCommand::migrate).build();
		migrateModArg.addChild(migrateShardTypeArg);
		migrateShardArg.addChild(migrateModArg);
		migrateCommand.addChild(migrateShardArg);
		library.addChild(migrateCommand);

		parent.addChild(library);
	}
}
