package dev.compilin.mc.shulkershop

import com.google.common.base.Strings
import com.mojang.brigadier.Command
import com.mojang.brigadier.CommandDispatcher
import com.mojang.brigadier.Message
import com.mojang.brigadier.arguments.IntegerArgumentType
import com.mojang.brigadier.arguments.StringArgumentType
import com.mojang.brigadier.builder.ArgumentBuilder
import com.mojang.brigadier.builder.LiteralArgumentBuilder
import com.mojang.brigadier.context.CommandContext
import com.mojang.brigadier.exceptions.CommandExceptionType
import com.mojang.brigadier.exceptions.CommandSyntaxException
import com.mojang.brigadier.tree.CommandNode
import com.mojang.brigadier.tree.LiteralCommandNode
import dev.compilin.mc.shulkershop.Config.CREATE_ITEM
import dev.compilin.mc.shulkershop.Config.SELECTION_TIMEOUT
import dev.compilin.mc.shulkershop.Config.SELECT_ITEM
import dev.compilin.mc.shulkershop.SShopMod.Companion.getSelectionByPlayer
import dev.compilin.mc.shulkershop.SShopMod.Permission.*
import dev.compilin.mc.shulkershop.SShopMod.PlayerShopSelection
import dev.compilin.mc.shulkershop.ShulkerShop.Companion.UNLIMITED
import net.minecraft.command.argument.ItemStackArgument
import net.minecraft.command.argument.ItemStackArgumentType
import net.minecraft.command.argument.MessageArgumentType
import net.minecraft.entity.ItemEntity
import net.minecraft.entity.mob.ShulkerEntity
import net.minecraft.entity.player.PlayerEntity
import net.minecraft.item.ItemStack
import net.minecraft.nbt.CompoundTag
import net.minecraft.server.command.CommandManager.argument
import net.minecraft.server.command.CommandManager.literal
import net.minecraft.server.command.ServerCommandSource
import net.minecraft.server.network.ServerPlayerEntity
import net.minecraft.sound.SoundCategory
import net.minecraft.sound.SoundEvents
import net.minecraft.text.LiteralText
import net.minecraft.text.MutableText
import net.minecraft.text.Text
import net.minecraft.util.DyeColor
import net.minecraft.util.Formatting
import net.minecraft.util.Hand
import java.util.*
import java.util.function.Function
import java.util.regex.Pattern
import java.util.stream.Collectors
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.max

object SShopCommand {
    private val dyeColors = Arrays.stream(DyeColor.values()).collect(
        Collectors.toMap(
            { d: DyeColor -> d.getName().replace("_", "") }, Function.identity()
        )
    )

    fun register(dispatcher: CommandDispatcher<ServerCommandSource>) {
        val command: CommandNode<ServerCommandSource> = dispatcher.register(rootCommand)
        log.debug(
            """
    Command added : 
    ${printCommandNode(command)}
    """.trimIndent()
        )
    }

    // Currying for brievety
    private val rootCommand: LiteralArgumentBuilder<ServerCommandSource>
        get() =// Currying for brievety
            literal("shulker").then(
                literal("give")
                    .requires(GIVE_MOD_ITEMS::check)
                    .then(
                        literal("selector")
                            .executes { ctx: CommandContext<ServerCommandSource> -> executeGive(ctx) })
                    .then(
                        literal("creator")
                            .executes { ctx: CommandContext<ServerCommandSource> -> executeGive(ctx) })
            ).then(
                literal("delete")
                    .requires(DELETE_OWN_SHOP::check)
                    .executes(this::executeOnShop)
                    .then( // text argument rather than literal to avoid having it auto-complete accidentally
                        argument("force", StringArgumentType.word())
                            .requires(FORCE_DELETE_SHOP::check)
                            .executes(this::executeOnShop)
                    )
            ).then(
                literal("offers").executes(this::executeOnShop).then(
                    literal("list").executes(this::executeOnShop)
                ).then(
                    offersAddCommand
                ).then(
                    literal("delete").then(
                        argument("id", IntegerArgumentType.integer(1)).executes(this::executeOnShop)
                    )
                ).then(literal("edit").then(offersEditCommand))
            ).then(
                literal("set").requires(EDIT_OWN_SHOP::check).then(
                    literal("name").then(
                        argument("name", MessageArgumentType.message())
                            .executes(this::executeOnShop)
                    )
                ).then(
                    colorEnumArgument(
                        literal("color"),
                        { _: String, node: LiteralArgumentBuilder<ServerCommandSource> ->
                            node.executes { this.executeOnShop(it) }
                        }
                    ).then(
                        literal("default")
                            .executes(this::executeOnShop)
                    )
                )
            )
    private val offersAddCommand: ArgumentBuilder<ServerCommandSource, *>
        get() = itemStackArgument(
            literal("add"), "buycount", "buyitem", 64,
            { }
        ) { node: ArgumentBuilder<ServerCommandSource, *> ->
            itemStackArgument(
                node,
                "sellcount",
                "sellitem",
                64,
                { }) { node2: ArgumentBuilder<ServerCommandSource, *> ->
                node2.executesK(this::executeOnShop).thenK(
                    literal("unlimited").executesK(this::executeOnShop)
                ).thenK(
                    argument("maxuses", IntegerArgumentType.integer(0)).executesK(this::executeOnShop)
                )
            }
        }
    private val offersEditCommand: ArgumentBuilder<ServerCommandSource, *>
        get() = argument("id", IntegerArgumentType.integer(1)).then(
            itemStackArgument(
                literal("bought"), "buycount", "buyitem", 64,
                { node: ArgumentBuilder<ServerCommandSource, *> -> node.executes(this::executeOnShop) }
            ) { node: ArgumentBuilder<ServerCommandSource, *> -> node.executes(this::executeOnShop) }
        ).then(
            itemStackArgument(
                literal("sold"), "sellcount", "sellitem", 64,
                { node: ArgumentBuilder<ServerCommandSource, *> -> node.executes(this::executeOnShop) }
            ) { node: ArgumentBuilder<ServerCommandSource, *> -> node.executes(this::executeOnShop) }
        ).then(
            literal("limit").then(
                argument("remaining", IntegerArgumentType.integer(0, UNLIMITED - 1))
                    .executes(this::executeOnShop)
            ).then(
                literal("unlimited").executes(this::executeOnShop)
            ).then(
                literal("set").then(
                    argument("maxuses", IntegerArgumentType.integer(0, UNLIMITED - 1))
                        .executes(this::executeOnShop)
                ).then(
                    literal("unlimited").executes(this::executeOnShop)
                )
            ).then(
                literal("add").then(
                    argument("uses", IntegerArgumentType.integer()).executes(this::executeOnShop)
                )
            )
        )

    /*	*****************
		 GIVE subcommand
		***************** */
    @Throws(CommandSyntaxException::class)
    private fun executeGive(ctx: CommandContext<ServerCommandSource>): Int {
        val player: ServerPlayerEntity = ctx.source.player
        when (ctx.nodes[2].node.name) {
            "selector" -> givePlayerItem(
                player, SELECT_ITEM()
                    .createStack(1, false)
            )
            "creator" -> givePlayerItem(
                player, CREATE_ITEM()
                    .createStack(1, false)
            )
            else -> throw IllegalStateException("Unknown subcmd for /" + ctx.input)
        }
        return 1
    }

    /**
     * Gives the player an item. Copied from GiveCommand
     * @param player target player
     * @param itemstack item stack to give
     */
    private fun givePlayerItem(player: PlayerEntity, itemstack: ItemStack) {
        // Code copied from GiveCommand
        if (player.inventory.insertStack(itemstack) && itemstack.isEmpty) { // ItemStack successfully added to inventory
            itemstack.count = 1
            player.dropItem(itemstack, false)?.setDespawnImmediately()
            player.world.playSound(
                null, player.x, player.y, player.z,
                SoundEvents.ENTITY_ITEM_PICKUP, SoundCategory.PLAYERS, 0.2f,
                ((player.random.nextFloat() - player.random.nextFloat()) * 0.7f + 1.0f) * 2.0f
            )
            player.playerScreenHandler.sendContentUpdates()
        } else {
            val itementity: ItemEntity? = player.dropItem(itemstack, false)
            if (itementity != null) {
                itementity.resetPickupDelay()
                itementity.owner = player.uuid
            }
        }
    }

    /*	***********************
		 Shop-editing commands  (set, offers, delete)
		*********************** */
    @Throws(CommandSyntaxException::class)
    private fun executeOnShop(ctx: CommandContext<ServerCommandSource>): Int {
        val player: ServerPlayerEntity = ctx.source.player
        val selected: Optional<PlayerShopSelection> = getSelectionByPlayer(player.uuid, true)
        if (!selected.isPresent) {
            ctx.source.sendFeedback(noShopSelectedMessage, true)
            return 0
        }
        selected.get().refreshTimeout()
        val shop: ShulkerShop = selected.get().shop
        try {
            return when (nodeName(ctx, 1)) {
                "delete" -> executeDelete(ctx, shop)
                "offers" -> executeOffers(ctx, shop)
                "set" -> executeSet(ctx, shop)
                else -> throw IllegalArgumentException("Unknown subcmd:" + nodeName(ctx, 1))
            }
        } catch (ex: IndexOutOfBoundsException) {
            ctx.source.sendError(
                LiteralText(
                    "Invalid offer ID! Use \"/shulker offers list\" to see offers by ID"
                )
            )
            return 0
        } catch (ex: Exception) {
            log.error("Exception occured in command /" + ctx.input, ex)
            throw ex
        }
    }

    /*	*******************
		 OFFERS subcommand
		******************* */
    @Throws(CommandSyntaxException::class)
    private fun executeOffers(ctx: CommandContext<ServerCommandSource>, shop: ShulkerShop): Int {
        shop.updateOffersStock()
        when (if (ctx.nodes.size > 2) nodeName(ctx, 2) else "list") {
            "list" -> {
                val message: MutableText = LiteralText("Current offers in ")
                    .append(shop.name.shallowCopy().formatted(Formatting.BOLD))
                    .append(":")
                if (shop.offers.isEmpty()) {
                    message.append(" None")
                } else {
                    val format = """
                        
                        % ${floor(log10(shop.offers.size.toDouble())).toInt() + 1}d. 
                        """.trimIndent()
                    for ((i, offer) in shop.shulkerOffers.withIndex()) {
                        message.append(String.format(format, i + 1)).append(offer.toTextComponent())
                    }
                }
                ctx.source.sendFeedback(message, true)
            }
            "add" -> {
                if (!checkPermission(ctx.source, shop, false)) {
                    return 0
                }
                val buyStack = getItemStack(ctx, 3, "buycount", "buyitem", null)
                val sellStack = getItemStack(
                    ctx, if (nodeName(ctx, 3) == "@hand" || nodeName(ctx, 3) == "@offhand") 4 else 5,
                    "sellcount", "sellitem", null
                )
                val maxUses = getOptionalArgument<Int>(ctx, "maxuses").orElse(UNLIMITED)
                if (buyStack.count > 2 * buyStack.maxCount) {
                    ctx.source.sendError(
                        LiteralText(
                            java.lang.String.format(
                                "Cannot set buycount to more than two stacks (%d) of the bought item",
                                2 * buyStack.maxCount
                            )
                        )
                    )
                    return 0
                }
                if (sellStack.count > sellStack.maxCount) {
                    ctx.source.sendError(
                        LiteralText(
                            java.lang.String.format(
                                "Cannot set sellcount to more than one stack (%d) of the sold item",
                                sellStack.maxCount
                            )
                        )
                    )
                    return 0
                }
                val offerPair: Pair<Int, ShulkerShopOffer> = shop.addOffer(buyStack, sellStack, maxUses)
                ctx.source.sendFeedback(
                    LiteralText("Successfully added ")
                        .append(offerPair.second.toTextComponent())
                        .append(" as offer " + offerPair.first), true
                )
            }
            "delete" -> {
                if (!checkPermission(ctx.source, shop, false)) {
                    return 0
                }
                val offer: ShulkerShopOffer = shop.deleteOffer(IntegerArgumentType.getInteger(ctx, "id") - 1)
                ctx.source.sendFeedback(
                    LiteralText("Successfully deleted offer ")
                        .append(offer.toTextComponent().formatted(Formatting.BOLD)), true
                )
            }
            "edit" -> return executeOffersEdit(ctx, shop)
            else -> throw IllegalStateException("Unknown subcmd for /" + ctx.input)
        }
        return 1
    }

    @Throws(CommandSyntaxException::class)
    fun executeOffersEdit(ctx: CommandContext<ServerCommandSource>, shop: ShulkerShop): Int {
        if (!checkPermission(ctx.source, shop, false)) {
            return 0
        }
        val offerId = IntegerArgumentType.getInteger(ctx, "id") - 1
        val offer: ShulkerShopOffer =
            shop.shulkerOffers[offerId] // IndexOutOfBoundsException caught in executeOnShop
        val notice: Text = LiteralText("")
        when (nodeName(ctx, 4)) {
            "bought" -> {
                val buyStack = getItemStack(
                    ctx, 5, "buycount", "buyitem",
                    offer.buyingStackFirst
                )
                offer.setBuyingStack(buyStack)
            }
            "sold" -> {
                val sellStack = getItemStack(
                    ctx, 5, "sellcount", "sellitem",
                    offer.getSellingStack()
                )
                offer.setSellingStack(sellStack)
            }
            "limit" -> {
                val maxUses: Int
                val limitType = if (ctx.nodes.size > 5) nodeName(ctx, 5) else null
                when (limitType) {
                    null -> {
                        maxUses = UNLIMITED
                    }
                    "remaining" -> { // Prevent over/underflow just in case
                        maxUses = (offer.uses.toLong() + IntegerArgumentType.getInteger(ctx, "remaining"))
                            .coerceIn(Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong()).toInt()
                    }
                    "set" -> {
                        maxUses = IntegerArgumentType.getInteger(ctx, "maxuses")
                    }
                    "add" -> { // Prevent over/underflow just in case
                        maxUses = (offer.getUseLimit().toLong() + IntegerArgumentType.getInteger(ctx, "uses"))
                            .coerceIn(Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong()).toInt()
                    }
                    else -> {
                        throw IllegalArgumentException("Unknown subcommand for /" + ctx.input)
                    }
                }
                if (maxUses < 0) {
                    ctx.source.sendError(LiteralText("Can't set negative max uses!"))
                    return 0
                }
                if (maxUses >= UNLIMITED && limitType != null) {
                    ctx.source.sendError(LiteralText("Can't set negative max uses!"))
                    return 0
                }
                offer.setUseLimit(max(maxUses, offer.uses)) // Don't set maxUse below uses
            }
            else -> throw IllegalArgumentException("Unknown subcommand for /" + ctx.input)
        }
        shop.updateOffersStock()
        ctx.source.sendFeedback(
            LiteralText(String.format("Updated offer %d:\n", offerId))
                .append(offer.toTextComponent())
                .append(notice), true
        )
        return 1
    }

    /*	*****************
		 NAME subcommand
		***************** */
    @Throws(CommandSyntaxException::class)
    private fun executeSet(ctx: CommandContext<ServerCommandSource>, shop: ShulkerShop): Int {
        val player: PlayerEntity = ctx.source.player
        return when (nodeName(ctx, 2)) {
            "name" -> {
                var message: MutableText = LiteralText("")
                var name: Text = MessageArgumentType.getMessage(ctx, "name")
                val shopHasPlayerName = Pattern.compile(
                    ".*\\b\\Q${player.gameProfile.name}\\E\\b.*",  // Given name contains player's name
                    Pattern.CASE_INSENSITIVE
                ).matcher(name.asString()).matches()
                if (shop.ownerId == player.uuid &&  // Player is shop's owner
                    !shopHasPlayerName && MAKE_ANONYMOUS_SHOP.check(player)
                ) {
                    message.append(
                        LiteralText(
                            " (Note: Your username was prepended as you do not have the permission to make anonymous shops)"
                        ).formatted(Formatting.YELLOW)
                    )
                    name = LiteralText(player.name.asString() + "'s ")
                        .append(name)
                }
                if (name.asString().length > 50) {
                    message = LiteralText("Name too long! Maximum length is 50 characters")
                        .append(message)
                    ctx.source.sendError(message)
                } else {
                    shop.name = name
                    message = LiteralText("Successfully changed shop's name to ")
                        .append(name).append(message)
                    ctx.source.sendFeedback(message, false)
                }
                1
            }
            "color" -> {
                val colorArg = nodeName(ctx, 3)
                val colorIndex = if (colorArg == "default") 16 else dyeColors[colorArg]!!.id
                val shulker: ShulkerEntity? = shop.getShulker()
                if (shulker == null) {
                    log.error("Editted shop does not have a reference to a shulker")
                    ctx.source.sendError(
                        LiteralText("An error occured")
                            .formatted(Formatting.RED)
                    )
                    return 0
                }
                val data = CompoundTag()
                shulker.writeCustomDataToTag(data)
                data.putByte("Color", colorIndex.toByte())
                shulker.readCustomDataFromTag(data)
                1
            }
            else -> throw IllegalArgumentException("Unknown subcommand for /" + ctx.input)
        }
    }

    /*	*****************
		 DELETE subcommand
		***************** */
    @Throws(CommandSyntaxException::class)
    private fun executeDelete(ctx: CommandContext<ServerCommandSource>, shop: ShulkerShop): Int {
        if (!checkPermission(ctx.source, shop, true)) {
            return 0
        }
        val force = getOptionalArgument<String>(ctx, "force")
            .map { it.toLowerCase() == "force" }
            .orElse(false)
        if (force && !FORCE_DELETE_SHOP.checkOrSendError(ctx.source)) return 0
        if (!shop.inventory.isEmpty && !force) {
            val message = LiteralText("Can't delete a shop with a non-empty inventory !")
            if (FORCE_DELETE_SHOP.check(ctx.source)) {
                message.append(" (use \"/shulker delete force\" to force deletion)")
            }
            ctx.source.sendError(message)
            return 1
        }
        buildString {
            append("Deleting ")
            if (force) append("(forced) ")
            append("shop \"").append(shop.name.string).append("\" (ID ").append(shop.uuid).append(") of ")
            val player = ctx.source.minecraftServer.playerManager.getPlayer(shop.ownerId)
            if (player != null) {
                append(player.name)
            } else {
                append("<unknown name> (").append(shop.ownerId).append(")")
            }

            if (ctx.source.entity !is ServerPlayerEntity || ctx.source.player.uuid != shop.ownerId) {
                append(" by ").append(ctx.source.displayName)
            }
            if (!shop.inventory.isEmpty) {
                append(" (").append(
                    (0 until shop.inventory.size())
                        .filter { shop.inventory.getStack(it).isEmpty }
                        .count()
                ).append(" stacks lost)")
            }
        }.let(log::debug)
        shop.delete()
        return 1
    }

    /*	******************
		 Common utilities
		****************** */
    private inline fun <reified T> getOptionalArgument(
        ctx: CommandContext<ServerCommandSource>,
        name: String
    ): Optional<T> {
        return try {
            Optional.of(ctx.getArgument(name, T::class.java))
        } catch (ex: IllegalArgumentException) {
            Optional.empty()
        }
    }

    private val noShopSelectedMessage: Text
        get() {
            val message = LiteralText("No shops selected! ")
                .formatted(Formatting.YELLOW)
                .append(LiteralText("Sneak and right click with a ")/*.formatted(Formatting.WHITE)*/)
                .append(SELECT_ITEM().item.name)
                .append(" on a shulker shop to select it")
            if (SELECTION_TIMEOUT() < 61) {
                message.append("\n(note: selections will time-out after ${SELECTION_TIMEOUT()} minutes without activity)")
            }
            return message
        }

    @Throws(CommandSyntaxException::class)
    private fun checkPermission(src: ServerCommandSource, shop: ShulkerShop, delete: Boolean): Boolean {
        val player: PlayerEntity = src.player
        return if (player.uuid == shop.ownerId) {
            if (delete) DELETE_OWN_SHOP.checkOrSendError(player)
            else EDIT_OWN_SHOP.checkOrSendError(player)
        } else {
            if (delete) DELETE_OTHERS_SHOP.checkOrSendError(player)
            else EDIT_OTHERS_SHOP.checkOrSendError(player)
        }
    }

    private fun <S : ArgumentBuilder<ServerCommandSource, S>> colorEnumArgument(
        parentNode: S, then: (String, LiteralArgumentBuilder<ServerCommandSource>) -> Unit,
    ): S {
        dyeColors.keys.forEach { v: String ->
            val lit: LiteralArgumentBuilder<ServerCommandSource> = literal(v)
            then(v, lit)
            parentNode.then(lit)
        }
        return parentNode
    }

    private fun itemStackArgument(
        parentNode: ArgumentBuilder<ServerCommandSource, *>,
        countArg: String,
        itemArg: String,
        maxCount: Int,
        thenCount: (ArgumentBuilder<ServerCommandSource, *>) -> Unit,
        then: (ArgumentBuilder<ServerCommandSource, *>) -> Unit
    ): ArgumentBuilder<ServerCommandSource, *> {
        val itemArgNode: ArgumentBuilder<ServerCommandSource, *> =
            argument(itemArg, ItemStackArgumentType.itemStack())
        val handNode: ArgumentBuilder<ServerCommandSource, *> = literal("@hand")
        val offHandNode: ArgumentBuilder<ServerCommandSource, *> = literal("@offhand")
        then(itemArgNode)
        then(handNode)
        then(offHandNode)
        val itemCountArgNode: ArgumentBuilder<ServerCommandSource, *> =
            argument(countArg, IntegerArgumentType.integer(1, maxCount)).then(itemArgNode)
        thenCount(itemCountArgNode)
        parentNode.then(itemCountArgNode)
        parentNode.then(handNode)
        parentNode.then(offHandNode)
        return parentNode
    }

    @Throws(CommandSyntaxException::class)
    private fun getItemStack(
        ctx: CommandContext<ServerCommandSource>, argId: Int, countArg: String, itemArg: String,
        baseStack: ItemStack?
    ): ItemStack {
        return if (nodeName(ctx, argId) == "@hand" || nodeName(ctx, argId) == "@offhand") {
            ctx.source.player.getStackInHand(
                if (nodeName(ctx, argId) == "@hand") Hand.MAIN_HAND else Hand.OFF_HAND
            ).copy()
        } else {
            val sellCount = IntegerArgumentType.getInteger(ctx, countArg)
            val sellStack: ItemStack
            if (baseStack != null) {
                sellStack = getOptionalArgument<ItemStackArgument>(ctx, itemArg)
                    .map { input: ItemStackArgument -> input.createStack(sellCount, false) }
                    .orElseGet {
                        val stack = baseStack.copy()
                        stack.count = sellCount
                        stack
                    }
            } else {
                sellStack = ItemStackArgumentType.getItemStackArgument(ctx, itemArg).createStack(sellCount, false)
            }
            if (sellCount > sellStack.maxCount) {
                throw SShopCommandException(
                    java.lang.String.format(
                        "Specified item count (%d) exceeds item's stack limit (%d)",
                        sellCount, sellStack.maxCount
                    )
                ).create()
            }
            sellStack
        }
    }

    fun printCommandNode(node: CommandNode<ServerCommandSource>): String {
        val sb = StringBuilder()
        printCommandNode(node, sb, 0, false)
        return sb.toString()
    }

    fun printCommandNode(
        node: CommandNode<ServerCommandSource>,
        out: StringBuilder,
        indentLevel: Int,
        optional: Boolean
    ) {
        if (node is LiteralCommandNode<*>) {
            out.append(node.getName())
        } else {
            out.append(if (optional) '[' else '<')
                .append(node.name)
                .append(if (optional) ']' else '>')
        }
        when {
            node.children.isEmpty() -> {
                out.append('\n')
            }
            node.children.size == 1 -> {
                out.append(' ')
                printCommandNode(
                    node.children.iterator().next(), out, indentLevel,
                    optional || node.command != null
                )
            }
            node.children.size > 1 -> {
                out.append('\n')
                node.children.forEach {
                    out.append(Strings.repeat("\t", indentLevel + 1))
                    printCommandNode(it, out, indentLevel + 1, optional || node.command != null)
                }
            }
        }
    }

    private fun nodeName(ctx: CommandContext<ServerCommandSource>, id: Int): String {
        return ctx.nodes[id].node.name
    }

    private fun interface OptionalMapper<T, U> {
        @Throws(Throwable::class)
        fun accept(t: T): U
    }

    class SShopCommandException(private val message: Message) : CommandExceptionType {

        constructor(message: String) : this(LiteralText(message))

        fun create(): CommandSyntaxException {
            return CommandSyntaxException(this, message)
        }
    }

    // Kotlin extensions to deal with wonky generic typechecking
    fun <E, F : ArgumentBuilder<E, F>> ArgumentBuilder<E, F>.executesK(cmd: Command<E>): ArgumentBuilder<E, F> =
        this.executes(cmd)

    fun <E, F : ArgumentBuilder<E, F>> ArgumentBuilder<E, F>.thenK(cmd: ArgumentBuilder<E, *>): ArgumentBuilder<E, F> =
        this.then(cmd)

    fun <E, F : ArgumentBuilder<E, F>> ArgumentBuilder<E, F>.thenK(cmd: CommandNode<E>): ArgumentBuilder<E, F> =
        this.then(cmd)

}