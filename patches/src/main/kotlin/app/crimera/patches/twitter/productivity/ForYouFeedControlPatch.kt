/*
 * Copyright (C) 2026 piko <https://github.com/crimera/piko>
 *
 * See the included NOTICE file for GPLv3 §7(b) terms that apply to this code.
 */

package app.crimera.patches.twitter.productivity

import app.crimera.patches.twitter.ads.timelineEntryHook.hideRecommendationItemsPatch
import app.crimera.patches.twitter.misc.extension.twitterInitHook
import app.crimera.patches.twitter.misc.settings.settingsPatch
import app.crimera.patches.twitter.utils.Constants.COMPATIBILITY_X
import app.crimera.patches.twitter.utils.Constants.PATCHES_DESCRIPTOR
import app.crimera.patches.twitter.utils.enableSettings
import app.morphe.patcher.Fingerprint
import app.morphe.patcher.extensions.InstructionExtensions.addInstruction
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.instructions
import app.morphe.patcher.fieldAccess
import app.morphe.patcher.methodCall
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.mutableClassDefBy
import app.morphe.patcher.patch.patch
import app.morphe.patcher.string
import app.morphe.patcher.util.getReference
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod.Companion.toMutable
import app.morphe.util.indexOfFirstInstructionOrThrow
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.NarrowLiteralInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodParameter
import com.android.tools.smali.dexlib2.builder.MutableMethodImplementation

private const val CONTROL_DESCRIPTOR = "$PATCHES_DESCRIPTOR/ForYouFeedControl;"

private const val RECYCLER_VIEW = "Landroidx/recyclerview/widget/RecyclerView;"

private object HomeTimelineClassFingerprint : Fingerprint(
    returnType = "V",
    parameters = emptyList(),
    filters = listOf(
        string("home_timeline_disable_home_scroll_position_restoration_on_cold_launch_enabled"),
    ),
)

private object HomeTimelineRenderFingerprint : Fingerprint(
    classFingerprint = HomeTimelineClassFingerprint,
    returnType = "V",
    filters = listOf(
        fieldAccess(opcode = Opcode.SGET_OBJECT, name = "RENDERING_STARTED"),
        methodCall(opcode = Opcode.INVOKE_VIRTUAL, parameters = listOf("I", "I", "Z"), returnType = "V"),
        fieldAccess(opcode = Opcode.SGET_OBJECT, name = "RENDERING_COMPLETED"),
    ),
)

private object HomeTimelineViewFingerprint : Fingerprint(
    classFingerprint = HomeTimelineRenderFingerprint,
    returnType = "V",
    parameters = listOf("Lcom/twitter/ui/list/v;"),
)

private data class RecyclerViewFieldChain(
    val timelineList: FieldReference,
    val listView: FieldReference,
    val recyclerView: FieldReference,
)

@Suppress("unused")
val forYouFeedControlPatch =
    patch(
        name = "For You feed time windows",
        description = "Adds three daily 10-minute For You sessions, an overnight feed pause, and a one-way activation setting.",
    ) {
        compatibleWith(COMPATIBILITY_X)
        dependsOn(settingsPatch, hideRecommendationItemsPatch)

        execute {
            enableSettings("productivityFeedControl")

            val applicationCreate = twitterInitHook.fingerprint.method
            val superOnCreate = applicationCreate.indexOfFirstInstructionOrThrow {
                opcode == Opcode.INVOKE_SUPER &&
                    getReference<MethodReference>()?.name == "onCreate"
            }
            applicationCreate.addInstruction(
                superOnCreate + 1,
                "invoke-static {p0}, $CONTROL_DESCRIPTOR->initialize(Landroid/app/Application;)V",
            )

            val recyclerViewFields = HomeTimelineRenderFingerprint.method.resolveRecyclerViewFieldChain()
            HomeTimelineRenderFingerprint.method.hookForYouRecyclerView(recyclerViewFields)
            HomeTimelineViewFingerprint.method.hookForYouRecyclerView(recyclerViewFields)
            resolveHomeTimelineRefresh().hookRefreshGuard()
            installTweetDetailNavigationGuard()
        }
    }

private fun MutableMethod.resolveRecyclerViewFieldChain(): RecyclerViewFieldChain {
    val matches = instructions.windowed(3).mapNotNull { (first, second, third) ->
        if (first.opcode != Opcode.IGET_OBJECT || second.opcode != Opcode.IGET_OBJECT ||
            third.opcode != Opcode.IGET_OBJECT
        ) return@mapNotNull null

        val firstInstruction = first as? TwoRegisterInstruction ?: return@mapNotNull null
        val secondInstruction = second as? TwoRegisterInstruction ?: return@mapNotNull null
        val thirdInstruction = third as? TwoRegisterInstruction ?: return@mapNotNull null
        val timelineList = first.getReference<FieldReference>() ?: return@mapNotNull null
        val listView = second.getReference<FieldReference>() ?: return@mapNotNull null
        val recyclerView = third.getReference<FieldReference>() ?: return@mapNotNull null

        if (firstInstruction.registerA != secondInstruction.registerB ||
            secondInstruction.registerA != thirdInstruction.registerB ||
            recyclerView.type != RECYCLER_VIEW
        ) return@mapNotNull null

        RecyclerViewFieldChain(timelineList, listView, recyclerView)
    }.distinct()

    return matches.singleOrNull()
        ?: throw PatchException("Could not uniquely resolve the For You timeline list")
}

private fun MutableMethod.hookForYouRecyclerView(fields: RecyclerViewFieldChain) {
    val returnIndex = instructions.indexOfLast { it.opcode == Opcode.RETURN_VOID }
    if (returnIndex < 0) throw PatchException("Missing timeline render return")

    addInstructions(
        returnIndex,
        """
            iget-object v0, p0, ${fields.timelineList}
            iget-object v0, v0, ${fields.listView}
            iget-object v0, v0, ${fields.recyclerView}
            invoke-static {p0, v0}, $CONTROL_DESCRIPTOR->attachTimeline(Ljava/lang/Object;$RECYCLER_VIEW)V
        """.trimIndent(),
    )
}

private fun resolveHomeTimelineRefresh(): MutableMethod =
    HomeTimelineRenderFingerprint.classDef.methods.singleOrNull { method ->
        method.returnType == "V" && method.parameterTypes.isEmpty() &&
            method.instructions.any { instruction ->
                (instruction as? NarrowLiteralInstruction)?.narrowLiteral == 0x4c
            }
    } ?: throw PatchException("Could not uniquely resolve the For You refresh action")

private fun MutableMethod.hookRefreshGuard() {
    addInstructions(
        0,
        """
            invoke-static {p0}, $CONTROL_DESCRIPTOR->canRefresh(Ljava/lang/Object;)Z
            move-result v0
            if-nez v0, :refresh_allowed
            return-void
            :refresh_allowed
            nop
        """.trimIndent(),
    )
}

private fun installTweetDetailNavigationGuard() {
    val detailClass = mutableClassDefBy("Lcom/twitter/tweetdetail/TweetDetailActivity;")
    val guard =
        """
            invoke-static {}, $CONTROL_DESCRIPTOR->shouldBlockNewPost()Z
            move-result v0
            if-eqz v0, :continue
            return-void
            :continue
        """.trimIndent()

    val existing = detailClass.methods.singleOrNull {
        it.name == "onNewIntent" && it.returnType == "V" &&
            it.parameterTypes == listOf("Landroid/content/Intent;")
    }
    if (existing != null) {
        existing.addInstructions(0, guard)
        return
    }

    val superclass = detailClass.superclass
        ?: throw PatchException("Tweet detail activity has no superclass")

    detailClass.methods.add(
        ImmutableMethod(
            detailClass.type,
            "onNewIntent",
            listOf(ImmutableMethodParameter("Landroid/content/Intent;", null, null)),
            "V",
            AccessFlags.PROTECTED.value,
            null,
            null,
            MutableMethodImplementation(3),
        ).toMutable().apply {
            addInstructions(
                0,
                """
                    $guard
                    invoke-super {p0, p1}, $superclass->onNewIntent(Landroid/content/Intent;)V
                    return-void
                """.trimIndent(),
            )
        },
    )
}
