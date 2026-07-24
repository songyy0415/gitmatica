package me.niicide.lvc.gui;

import me.niicide.lvc.overlay.LvcTrackingOverlayService;
import me.niicide.lvc.overlay.LvcTrackingOverlay;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import javax.annotation.Nullable;
import net.minecraft.client.Minecraft;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import fi.dy.masa.litematica.config.Configs;
import fi.dy.masa.litematica.config.Hotkeys;
import fi.dy.masa.litematica.gui.GuiMainMenu.ButtonListenerChangeMenu;
import fi.dy.masa.litematica.render.infohud.InfoHud;
import fi.dy.masa.litematica.render.infohud.RenderPhase;
import fi.dy.masa.litematica.schematic.placement.SchematicPlacement;
import fi.dy.masa.litematica.schematic.verifier.SchematicVerifier;
import fi.dy.masa.litematica.schematic.verifier.SchematicVerifier.BlockMismatch;
import fi.dy.masa.litematica.schematic.verifier.SchematicVerifier.MismatchType;
import fi.dy.masa.litematica.schematic.verifier.SchematicVerifier.MismatchRenderPos;
import me.niicide.lvc.integration.litematica.verifier.VerifierInventoryPreview;
import me.niicide.lvc.integration.litematica.verifier.GitmaticaVerifiers;
import me.niicide.lvc.integration.litematica.verifier.VerifierMismatchMetadata;
import fi.dy.masa.litematica.util.BlockInfoListType;
import fi.dy.masa.malilib.gui.GuiListBase;
import fi.dy.masa.malilib.gui.Message.MessageType;
import fi.dy.masa.malilib.gui.button.ButtonBase;
import fi.dy.masa.malilib.gui.button.ButtonGeneric;
import fi.dy.masa.malilib.gui.button.IButtonActionListener;
import fi.dy.masa.malilib.gui.interfaces.ISelectionListener;
import fi.dy.masa.malilib.interfaces.ICompletionListener;
import fi.dy.masa.malilib.util.GuiUtils;
import fi.dy.masa.malilib.util.InfoUtils;
import fi.dy.masa.malilib.util.StringUtils;
import me.niicide.lvc.LvcDiagnostics;
import me.niicide.lvc.LvcFriendlyErrors.Operation;
import me.niicide.lvc.diff.LvcSpatialDiffGroups.Group;
import me.niicide.lvc.diff.LvcSpatialDiffGroups.Kind;
import me.niicide.lvc.diff.LvcVerifierDiffGroups;
import me.niicide.lvc.diff.LvcVerifierDiffGroups.Entry;
import me.niicide.lvc.overlay.LvcTrackingOverlayRevision;
import me.niicide.lvc.task.LvcOperationHandle;
import me.niicide.lvc.task.LvcRefreshMarker;
import me.niicide.lvc.task.LvcSemanticOverlayTask;
import me.niicide.lvc.task.LvcTaskCallbacks;
import me.niicide.lvc.task.LvcTaskRegistry;
import me.niicide.lvc.task.LvcTaskScheduling;
import me.niicide.lvc.gui.widgets.LvcChangeEntry;
import me.niicide.lvc.gui.widgets.LvcChangeViewerView;
import me.niicide.lvc.gui.widgets.WidgetListLvcChangeViewerEntries;
import me.niicide.lvc.gui.widgets.WidgetLvcChangeViewerEntry;

public class GuiLvcChangeViewer extends GuiListBase<LvcChangeEntry, WidgetLvcChangeViewerEntry, WidgetListLvcChangeViewerEntries>
        implements ISelectionListener<LvcChangeEntry>, ICompletionListener, LvcChangeViewerView
{
    private static final String SWITCH_OVERLAY_OPERATION = "LVC Switch Change Overlay";
    private static final LvcChangeViewerStateStore<SchematicVerifier, LvcChangeEntry, GroupKindKey, ChangeFilter>
            SAVED_STATE = new LvcChangeViewerStateStore<>();

    private final Path repositoryDirectory;
    private SchematicPlacement placement;
    private SchematicVerifier verifier;
    private final Map<BlockPos, Boolean> groupExpanded = new HashMap<>();
    private final Map<GroupKindKey, Boolean> kindExpanded = new HashMap<>();
    private ChangeFilter filter;
    private LvcTrackingOverlayRevision overlayRevision;
    private boolean overlaySwitchInProgress;
    private List<Group<Entry>> spatialGroups = List.of();
    private final List<LvcChangeEntry> inventoryPreviewSelections = new ArrayList<>();

    public GuiLvcChangeViewer(SchematicPlacement placement)
    {
        super(10, 60);
        this.placement = placement;
        this.verifier = placement.getSchematicVerifier();
        this.repositoryDirectory = Objects.requireNonNull(
                LvcTrackingOverlayService.semanticTrackingRepositoryDirectory(placement.getSchematicFile()),
                "Gitmatica tracking overlay repository");
        this.overlayRevision = LvcTrackingOverlayService.trackingOverlayRevision(this.repositoryDirectory, placement);
        this.filter = SAVED_STATE.filter(this.verifier, ChangeFilter.ALL);
        this.inventoryPreviewSelections.addAll(SAVED_STATE.inventoryPreviewSelections(this.verifier));
        this.updateTitle();
        GitmaticaVerifiers.extension(this.verifier).gitmatica$setChangeInfoHud(true);
        this.restoreExpansionState();
    }

    @Override
    protected int getBrowserWidth()
    {
        return this.getScreenWidth() - 20;
    }

    @Override
    public int getBrowserHeight()
    {
        return this.getScreenHeight() - 94;
    }

    @Override
    public void initGui()
    {
        this.refreshSpatialGroups();
        this.syncRenderThroughMismatchFilter();
        super.initGui();
        this.restoreSelections();

        int x = 12;
        int y = 20;

        x += this.createButton(x, y, -1, ButtonListener.Type.START) + 4;
        x += this.createButton(x, y, -1, ButtonListener.Type.STOP) + 4;
        x += this.createButton(x, y, -1, ButtonListener.Type.RESET_VERIFIER) + 4;
        x += this.createButton(x, y, -1, ButtonListener.Type.SET_LIST_TYPE) + 4;
        x += this.createButton(x, y, -1, ButtonListener.Type.RESET_IGNORED) + 4;
        this.createButton(x, y, -1, ButtonListener.Type.TOGGLE_INFO_HUD);

        y += 22;
        x = 12;
        x += this.createButton(x, y, -1, ButtonListener.Type.FILTER_ALL) + 4;
        x += this.createButton(x, y, -1, ButtonListener.Type.FILTER_ADDED) + 4;
        x += this.createButton(x, y, -1, ButtonListener.Type.FILTER_CHANGED) + 4;
        x += this.createButton(x, y, -1, ButtonListener.Type.FILTER_REMOVED) + 4;
        x += this.createButton(x, y, -1, ButtonListener.Type.FILTER_INVENTORY) + 4;
        this.createButton(x, y, -1, ButtonListener.Type.TOGGLE_OVERLAY_REVISION);

        int navigationY = this.getScreenHeight() - 24;
        this.addStatusLabels(navigationY - 6);
        this.addNavigationButtons(navigationY);
    }

    private void addNavigationButtons(int y)
    {
        ButtonListenerChangeMenu.ButtonType type = ButtonListenerChangeMenu.ButtonType.MAIN_MENU;
        String label = StringUtils.translate(type.getLabelKey());
        int buttonWidth = this.getStringWidth(label) + 20;
        int menuX = this.getScreenWidth() - buttonWidth - 10;
        ButtonGeneric button = new ButtonGeneric(menuX, y, buttonWidth, 20, label);
        this.addButton(button, new ButtonListenerChangeMenu(type, this.getParent()));

        label = StringUtils.translate("gitmatica.gui.title.lvc_project_manager");
        buttonWidth = this.getStringWidth(label) + 20;
        button = new ButtonGeneric(menuX - buttonWidth - 4, y, buttonWidth, 20, label);
        this.addButton(button, (ignored, ignoredMouseButton) ->
                LvcSchematicPlacementRowActions.openProjectManager(this.placement));
    }

    private void addStatusLabels(int y)
    {
        if (this.verifier.isActive())
        {
            String str = StringUtils.translate("litematica.gui.label.schematic_verifier.status.verifying",
                    this.verifier.getUnseenChunks(), this.verifier.getTotalChunks());
            this.addLabel(12, y, 100, 12, 0xFFF0F0F0, str);
            return;
        }

        if (this.verifier.isFinished())
        {
            ChangeCounts counts = this.changeCounts();
            String str = StringUtils.translate("gitmatica.gui.label.lvc_change_viewer.status.blocks",
                    counts.added(), counts.removed(), counts.changedBlocks(), counts.changedStates());
            this.addLabel(12, y, 100, 12, 0xFFF0F0F0, str);
            str = StringUtils.translate("gitmatica.gui.label.lvc_change_viewer.status.inventories");
            this.addLabel(12, y + 14, 100, 12, 0xFFF0F0F0, str);
            int changedX = 12 + this.getStringWidth(str) + 4;
            str = StringUtils.translate("gitmatica.gui.label.lvc_change_viewer.status.inventories_changed",
                    counts.changedInventories());
            this.addLabel(changedX, y + 14, 100, 12,
                    LvcChangeEntry.kindTextColor(Kind.INVENTORIES_CHANGED), str);
        }
    }

    private int createButton(int x, int y, int width, ButtonListener.Type type)
    {
        ButtonListener listener = new ButtonListener(type, this);
        boolean enabled = true;
        String label = "";

        switch (type)
        {
            case START:
                if (this.verifier.isPaused())
                {
                    label = StringUtils.translate("litematica.gui.button.schematic_verifier.resume");
                }
                else
                {
                    label = StringUtils.translate("litematica.gui.button.schematic_verifier.start");
                    enabled = !this.verifier.isActive();
                }
                break;
            case STOP:
                label = StringUtils.translate("litematica.gui.button.schematic_verifier.stop");
                enabled = this.verifier.isActive();
                break;
            case RESET_VERIFIER:
                label = StringUtils.translate("litematica.gui.button.schematic_verifier.reset_verifier");
                enabled = this.verifier.isActive() || this.verifier.isPaused() || this.verifier.isFinished();
                break;
            case SET_LIST_TYPE:
                label = StringUtils.translate("litematica.gui.button.schematic_verifier.range_type",
                        this.placement.getSchematicVerifierType().getDisplayName());
                break;
            case RESET_IGNORED:
                label = StringUtils.translate("gitmatica.gui.button.lvc_change_viewer.reset_hidden");
                enabled = GitmaticaVerifiers.extension(this.verifier).gitmatica$hasIgnoredMismatches();
                break;
            case TOGGLE_INFO_HUD:
                boolean val = InfoHud.getInstance().isEnabled() && this.verifier.getShouldRenderText(RenderPhase.POST);
                String str = (val ? TXT_GREEN : TXT_RED) + StringUtils.translate("litematica.message.value." + (val ? "on" : "off")) + TXT_RST;
                label = StringUtils.translate("litematica.gui.button.schematic_verifier.toggle_info_hud", str);
                break;
            case FILTER_ALL:
                label = StringUtils.translate("gitmatica.gui.button.lvc_change_viewer.filter_all");
                enabled = this.filter != ChangeFilter.ALL;
                break;
            case FILTER_ADDED:
                label = StringUtils.translate("gitmatica.gui.button.lvc_change_viewer.filter_added");
                enabled = this.filter != ChangeFilter.ADDED;
                break;
            case FILTER_CHANGED:
                label = StringUtils.translate("gitmatica.gui.button.lvc_change_viewer.filter_changed");
                enabled = this.filter != ChangeFilter.CHANGED;
                break;
            case FILTER_REMOVED:
                label = StringUtils.translate("gitmatica.gui.button.lvc_change_viewer.filter_removed");
                enabled = this.filter != ChangeFilter.REMOVED;
                break;
            case FILTER_INVENTORY:
                label = StringUtils.translate("gitmatica.gui.button.lvc_change_viewer.filter_inventory");
                enabled = this.filter != ChangeFilter.INVENTORY;
                break;
            case TOGGLE_OVERLAY_REVISION:
                String revision = StringUtils.translate(this.overlayRevision == LvcTrackingOverlayRevision.CURRENT ?
                        "gitmatica.gui.label.lvc_change_viewer.overlay_current" :
                        "gitmatica.gui.label.lvc_change_viewer.overlay_parent");
                label = StringUtils.translate("gitmatica.gui.button.lvc_change_viewer.overlay_revision", revision);
                break;
        }

        if (this.overlaySwitchInProgress)
        {
            enabled = false;
        }

        if (width == -1)
        {
            width = this.getStringWidth(label) + 10;
        }

        ButtonGeneric button = new ButtonGeneric(x, y, width, 20, label);
        button.setEnabled(enabled);
        this.addButton(button, listener);
        return width;
    }

    public SchematicPlacement getPlacement()
    {
        return this.placement;
    }

    private void updateTitle()
    {
        this.title = StringUtils.translate("gitmatica.gui.title.lvc_change_viewer", this.placement.getName());
    }

    private void switchOverlayRevision()
    {
        if (this.overlaySwitchInProgress)
        {
            return;
        }

        ClientLevel clientLevel = Minecraft.getInstance().level;

        if (clientLevel == null)
        {
            LvcGuiMessages.show(MessageType.ERROR, "gitmatica.error.lvc_project.no_world");
            return;
        }

        Optional<LvcOperationHandle> handle = LvcTaskRegistry.tryAcquireBackground(
                SWITCH_OVERLAY_OPERATION, this.repositoryDirectory);

        if (handle.isEmpty())
        {
            LvcGuiMessages.show(MessageType.ERROR, "gitmatica.error.lvc_project.operation_running",
                    LvcTaskRegistry.activeOperationName());
            return;
        }

        LvcTrackingOverlayRevision targetRevision = this.overlayRevision == LvcTrackingOverlayRevision.CURRENT ?
                LvcTrackingOverlayRevision.PARENT : LvcTrackingOverlayRevision.CURRENT;
        this.overlaySwitchInProgress = true;

        try
        {
            LvcSemanticOverlayTask task = new LvcSemanticOverlayTask(
                    handle.get(),
                    this.repositoryDirectory,
                    this.placement.getName(),
                    clientLevel,
                    this,
                    true,
                    targetRevision,
                    LvcTaskCallbacks.of(
                            overlay -> this.finishOverlaySwitch(overlay, targetRevision),
                            this::failOverlaySwitch,
                            this::abortOverlaySwitch
                    )
            );
            LvcTaskScheduling.scheduleForWorld(clientLevel, task);
        }
        catch (Exception e)
        {
            LvcTaskRegistry.release(handle.get());
            this.failOverlaySwitch(e);
        }
    }

    private void finishOverlaySwitch(LvcTrackingOverlay overlay,
                                     LvcTrackingOverlayRevision targetRevision)
    {
        SAVED_STATE.clearInventoryPreviewSelections(this.verifier);
        this.placement = overlay.placement();
        this.verifier = overlay.verifier();
        this.overlayRevision = targetRevision;
        this.overlaySwitchInProgress = false;
        this.inventoryPreviewSelections.clear();
        this.spatialGroups = List.of();
        SAVED_STATE.saveFilter(this.verifier, this.filter);
        this.saveExpansionState();
        GitmaticaVerifiers.extension(this.verifier).gitmatica$setChangeInfoHud(true);
        this.updateTitle();
        this.clearRefreshMarkerAfterOverlaySwitch();
        this.reinitializeIfOpen();
    }

    private void failOverlaySwitch(Exception exception)
    {
        this.overlaySwitchInProgress = false;
        LvcGuiMessages.showTaskError(Operation.LOAD_OVERLAY,
                "gitmatica.error.lvc_project.tracking_failed", exception);
        this.reinitializeIfOpen();
    }

    private void abortOverlaySwitch()
    {
        this.overlaySwitchInProgress = false;
        LvcDiagnostics.debug("change viewer overlay switch aborted repo='{}'", this.repositoryDirectory);
        this.reinitializeIfOpen();
    }

    private void clearRefreshMarkerAfterOverlaySwitch()
    {
        try
        {
            LvcRefreshMarker.delete(this.repositoryDirectory);
        }
        catch (Exception e)
        {
            LvcDiagnostics.warn("Failed to clear change-viewer overlay refresh marker repo='{}' error='{}'",
                    this.repositoryDirectory, e.getMessage());
        }
    }

    private void reinitializeIfOpen()
    {
        if (GuiUtils.getCurrentScreen() == this)
        {
            this.initGui();
        }
    }

    @Override
    public boolean isGroupExpanded(BlockPos groupAnchor)
    {
        return this.groupExpanded.getOrDefault(groupAnchor, true);
    }

    @Override
    public boolean isKindExpanded(BlockPos groupAnchor, Kind kind)
    {
        return this.kindExpanded.getOrDefault(new GroupKindKey(groupAnchor, kind), false);
    }

    @Override
    public boolean isKindVisible(Kind kind)
    {
        return this.filter.includes(kind);
    }

    @Override
    public boolean isGroupVisible(Group<Entry> group)
    {
        for (Kind kind : Kind.values())
        {
            if (this.isKindVisible(kind) && !group.entries(kind).isEmpty())
            {
                return true;
            }
        }

        return false;
    }

    private void setFilter(ChangeFilter filter)
    {
        this.filter = filter;
        SAVED_STATE.saveFilter(this.verifier, filter);
        this.syncSelectedMismatchPositions();
        this.syncRenderThroughMismatchFilter();
    }

    @Override
    public List<Group<Entry>> spatialGroups()
    {
        return this.spatialGroups;
    }

    private void refreshSpatialGroups()
    {
        this.spatialGroups = LvcVerifierDiffGroups.build(this.verifier);

        for (Group<Entry> group : this.spatialGroups)
        {
            this.groupExpanded.putIfAbsent(group.anchor(), true);

            for (Kind kind : Kind.values())
            {
                this.kindExpanded.putIfAbsent(new GroupKindKey(group.anchor(), kind),
                        LvcChangeEntry.mismatchCount(group, kind) > 0);
            }
        }

        WidgetLvcChangeViewerEntry.setMaxNameLengths(this.allChangeEntries());
    }

    private List<BlockMismatch> allChangeEntries()
    {
        List<BlockMismatch> entries = new ArrayList<>();

        for (Group<Entry> group : this.spatialGroups)
        {
            group.allEntries().stream().map(Entry::mismatch).forEach(entries::add);
        }

        return entries;
    }

    private ChangeCounts changeCounts()
    {
        return new ChangeCounts(
                this.count(Kind.BLOCKS_ADDED),
                this.count(Kind.BLOCKS_REMOVED),
                this.count(Kind.BLOCKSTATE_CHANGED),
                this.count(Kind.BLOCKS_CHANGED),
                this.count(Kind.INVENTORIES_CHANGED)
        );
    }

    private int count(Kind kind)
    {
        int count = 0;

        for (Group<Entry> group : this.spatialGroups)
        {
            count += LvcChangeEntry.mismatchCount(group, kind);
        }

        return count;
    }

    @Override
    public void onTaskCompleted()
    {
        if (GuiUtils.getCurrentScreen() == this)
        {
            this.initGui();
            LvcDiagnostics.debug("change viewer verifier completed placement={} spatialGroups={} rows={}",
                    this.placement.getName(), this.spatialGroups.size(), this.allChangeEntries().size());
        }
    }

    @Nullable
    @Override
    public VerifierInventoryPreview lockedInventoryPreview()
    {
        for (int index = this.inventoryPreviewSelections.size() - 1; index >= 0; --index)
        {
            BlockMismatch mismatch = this.inventoryPreviewSelections.get(index).mismatch();

            if (mismatch != null && VerifierMismatchMetadata.inventoryPreview(mismatch) != null)
            {
                return VerifierMismatchMetadata.inventoryPreview(mismatch);
            }
        }

        return null;
    }

    @Override
    public SchematicVerifier verifier()
    {
        return this.verifier;
    }

    @Override
    public void hideMismatch(BlockMismatch mismatch)
    {
        GitmaticaVerifiers.extension(this.verifier).gitmatica$hideMismatch(mismatch);
        this.initGui();
    }

    @Override
    public void onSelectionChange(@Nullable LvcChangeEntry entry)
    {
        if (entry == null)
        {
            return;
        }

        if (entry.type() == LvcChangeEntry.Type.GROUP || entry.type() == LvcChangeEntry.Type.KIND ||
            entry.type() == LvcChangeEntry.Type.DATA)
        {
            this.normalizeSelectionHierarchy(entry);
            this.updateInventoryPreviewSelection(entry);
            this.saveSelections();
            this.syncSelectedMismatchPositions();

            if (!Configs.InfoOverlays.VERIFIER_OVERLAY_ENABLED.getBooleanValue())
            {
                String name = Configs.InfoOverlays.VERIFIER_OVERLAY_ENABLED.getName();
                String hotkeyName = Hotkeys.TOGGLE_VERIFIER_OVERLAY_RENDERING.getName();
                String hotkeyVal = Hotkeys.TOGGLE_VERIFIER_OVERLAY_RENDERING.getKeybind().getKeysDisplayString();
                InfoUtils.showGuiOrInGameMessage(MessageType.WARNING,
                        "litematica.message.warn.schematic_verifier.overlay_disabled", name, hotkeyName, hotkeyVal);
            }
        }
    }

    private void updateInventoryPreviewSelection(LvcChangeEntry selectedEntry)
    {
        if (this.isInventoryPreviewEntry(selectedEntry))
        {
            this.inventoryPreviewSelections.remove(selectedEntry);

            if (this.getListWidget().getSelectedEntries().contains(selectedEntry))
            {
                this.inventoryPreviewSelections.add(selectedEntry);
            }
        }

        this.pruneInventoryPreviewSelections();
    }

    private void pruneInventoryPreviewSelections()
    {
        Set<LvcChangeEntry> selections = this.getListWidget().getSelectedEntries();
        this.inventoryPreviewSelections.removeIf(entry ->
                !selections.contains(entry) || !this.selectionStillExists(entry) || !this.isInventoryPreviewEntry(entry));

        SAVED_STATE.saveInventoryPreviewSelections(this.verifier, this.inventoryPreviewSelections);
    }

    private boolean isInventoryPreviewEntry(LvcChangeEntry entry)
    {
        BlockMismatch mismatch = entry.mismatch();
        return entry.type() == LvcChangeEntry.Type.DATA &&
               entry.kind() == Kind.INVENTORIES_CHANGED &&
               mismatch != null && VerifierMismatchMetadata.inventoryPreview(mismatch) != null;
    }

    private void normalizeSelectionHierarchy(LvcChangeEntry selectedEntry)
    {
        Set<LvcChangeEntry> selections = this.getListWidget().getSelectedEntries();
        boolean selected = selections.contains(selectedEntry);

        switch (selectedEntry.type())
        {
            case GROUP -> this.updateGroupDescendantSelections(selectedEntry, selections, selected);
            case KIND ->
            {
                this.removeSelectedGroupAncestor(selectedEntry, selections);
                this.updateKindDescendantSelections(selectedEntry, selections, selected);
            }
            case DATA ->
            {
                this.removeSelectedGroupAncestor(selectedEntry, selections);
                this.removeSelectedKindAncestor(selectedEntry, selections);
            }
            default ->
            {
            }
        }
    }

    private void updateGroupDescendantSelections(LvcChangeEntry groupEntry,
                                                  Set<LvcChangeEntry> selections, boolean selected)
    {
        Group<Entry> group = this.groupFor(groupEntry.groupAnchor());

        if (group == null)
        {
            return;
        }

        for (Kind kind : Kind.values())
        {
            if (group.entries(kind).isEmpty())
            {
                continue;
            }

            LvcChangeEntry kindEntry = LvcChangeEntry.kind(group, groupEntry.groupNumber(), kind);
            this.updateSelection(selections, kindEntry, selected);

            for (Entry data : group.entries(kind))
            {
                this.updateSelection(selections,
                        LvcChangeEntry.data(group, groupEntry.groupNumber(), kind, data), selected);
            }
        }
    }

    private void updateKindDescendantSelections(LvcChangeEntry kindEntry,
                                                 Set<LvcChangeEntry> selections, boolean selected)
    {
        Group<Entry> group = this.groupFor(kindEntry.groupAnchor());

        if (group == null || kindEntry.kind() == null)
        {
            return;
        }

        for (Entry data : group.entries(kindEntry.kind()))
        {
            this.updateSelection(selections,
                    LvcChangeEntry.data(group, kindEntry.groupNumber(), kindEntry.kind(), data), selected);
        }
    }

    private void removeSelectedGroupAncestor(LvcChangeEntry entry, Set<LvcChangeEntry> selections)
    {
        selections.removeIf(candidate -> candidate.type() == LvcChangeEntry.Type.GROUP &&
                Objects.equals(candidate.groupAnchor(), entry.groupAnchor()));
    }

    private void removeSelectedKindAncestor(LvcChangeEntry entry, Set<LvcChangeEntry> selections)
    {
        selections.removeIf(candidate -> candidate.type() == LvcChangeEntry.Type.KIND &&
                candidate.kind() == entry.kind() &&
                Objects.equals(candidate.groupAnchor(), entry.groupAnchor()));
    }

    private void updateSelection(Set<LvcChangeEntry> selections, LvcChangeEntry entry, boolean selected)
    {
        if (selected)
        {
            selections.add(entry);
        }
        else
        {
            selections.remove(entry);
        }
    }

    private void saveSelections()
    {
        SAVED_STATE.saveSelections(this.verifier, this.getListWidget().getSelectedEntries());
    }

    private void restoreSelections()
    {
        Set<LvcChangeEntry> saved = SAVED_STATE.selections(this.verifier);

        if (saved == null)
        {
            return;
        }

        Set<LvcChangeEntry> restored = new HashSet<>();

        for (LvcChangeEntry entry : saved)
        {
            if (this.selectionStillExists(entry))
            {
                restored.add(entry);
            }
        }

        Set<LvcChangeEntry> selections = this.getListWidget().getSelectedEntries();
        selections.clear();
        selections.addAll(restored);
        this.pruneInventoryPreviewSelections();
        this.saveSelections();
        this.syncSelectedMismatchPositions();
    }

    private boolean selectionStillExists(LvcChangeEntry selection)
    {
        Group<Entry> group = this.groupFor(selection.groupAnchor());

        if (group == null)
        {
            return false;
        }

        return switch (selection.type())
        {
            case GROUP -> true;
            case KIND -> selection.kind() != null && !group.entries(selection.kind()).isEmpty();
            case DATA -> selection.kind() != null && selection.data() != null &&
                    group.entries(selection.kind()).contains(selection.data());
            default -> false;
        };
    }

    @Override
    public void toggleExpanded(LvcChangeEntry entry)
    {
        if (entry.type() == LvcChangeEntry.Type.GROUP && entry.groupAnchor() != null)
        {
            this.groupExpanded.put(entry.groupAnchor(), !this.isGroupExpanded(entry.groupAnchor()));
            this.saveExpansionState();
            this.getListWidget().refreshEntries();
        }
        else if (entry.type() == LvcChangeEntry.Type.KIND &&
                entry.groupAnchor() != null && entry.kind() != null)
        {
            GroupKindKey key = new GroupKindKey(entry.groupAnchor(), entry.kind());
            this.kindExpanded.put(key, !this.isKindExpanded(entry.groupAnchor(), entry.kind()));
            this.saveExpansionState();
            this.getListWidget().refreshEntries();
        }
    }

    private void restoreExpansionState()
    {
        LvcChangeViewerStateStore.Expansion<GroupKindKey> saved = SAVED_STATE.expansion(this.verifier);

        if (saved != null)
        {
            for (Map.Entry<?, Boolean> entry : saved.groups().entrySet())
            {
                if (entry.getKey() instanceof BlockPos groupAnchor)
                {
                    this.groupExpanded.put(groupAnchor, entry.getValue());
                }
            }
            this.kindExpanded.putAll(saved.kinds());
        }
    }

    private void saveExpansionState()
    {
        SAVED_STATE.saveExpansion(this.verifier, this.groupExpanded, this.kindExpanded);
    }

    private void syncSelectedMismatchPositions()
    {
        Map<SelectedPosition, MismatchRenderPos> positions = new LinkedHashMap<>();

        for (LvcChangeEntry selected : this.getListWidget().getSelectedEntries())
        {
            for (Entry data : this.entriesSelectedBy(selected))
            {
                for (BlockPos position : data.positions())
                {
                    MismatchType type = data.mismatch().mismatchType();
                    positions.putIfAbsent(new SelectedPosition(position, type), new MismatchRenderPos(type, position));
                }
            }
        }

        GitmaticaVerifiers.extension(this.verifier).gitmatica$setMismatchPositionsSelected(positions.values());
    }

    private void syncRenderThroughMismatchFilter()
    {
        if (this.filter == ChangeFilter.ALL)
        {
            GitmaticaVerifiers.extension(this.verifier).gitmatica$clearRenderThroughMismatchFilter();
            return;
        }

        Map<SelectedPosition, MismatchRenderPos> positions = new LinkedHashMap<>();

        for (Group<Entry> group : this.spatialGroups)
        {
            for (Entry entry : group.allEntries(this::isKindVisible))
            {
                for (BlockPos position : entry.positions())
                {
                    MismatchType type = entry.mismatch().mismatchType();
                    positions.putIfAbsent(
                            new SelectedPosition(position, type),
                            new MismatchRenderPos(type, position));
                }
            }
        }

        GitmaticaVerifiers.extension(this.verifier)
                .gitmatica$setRenderThroughMismatchFilter(positions.values());
    }

    private List<Entry> entriesSelectedBy(LvcChangeEntry selected)
    {
        if (selected.type() == LvcChangeEntry.Type.DATA && selected.data() != null &&
            selected.kind() != null && this.isKindVisible(selected.kind()))
        {
            return List.of(selected.data());
        }

        Group<Entry> group = this.groupFor(selected.groupAnchor());

        if (group == null)
        {
            return List.of();
        }

        if (selected.type() == LvcChangeEntry.Type.KIND &&
                selected.kind() != null && this.isKindVisible(selected.kind()))
        {
            return group.entries(selected.kind());
        }

        return selected.type() == LvcChangeEntry.Type.GROUP ?
                group.allEntries(this::isKindVisible) : List.of();
    }

    @Nullable
    private Group<Entry> groupFor(@Nullable BlockPos anchor)
    {
        return this.spatialGroups.stream()
                .filter(candidate -> Objects.equals(candidate.anchor(), anchor))
                .findFirst()
                .orElse(null);
    }

    @Override
    protected ISelectionListener<LvcChangeEntry> getSelectionListener()
    {
        return this;
    }

    @Override
    protected WidgetListLvcChangeViewerEntries createListWidget(int listX, int listY)
    {
        return new WidgetListLvcChangeViewerEntries(
                listX, listY, this.getBrowserWidth(), this.getBrowserHeight(), this, this);
    }

    private record GroupKindKey(BlockPos groupAnchor, Kind kind)
    {
    }

    private record SelectedPosition(BlockPos position, MismatchType type)
    {
        private SelectedPosition
        {
            position = position.immutable();
        }
    }

    private record ChangeCounts(int added, int removed, int changedStates, int changedBlocks, int changedInventories)
    {
    }

    private enum ChangeFilter
    {
        ALL,
        ADDED,
        CHANGED,
        REMOVED,
        INVENTORY;

        private boolean includes(Kind kind)
        {
            return switch (this)
            {
                case ALL -> true;
                case ADDED -> kind == Kind.BLOCKS_ADDED;
                case CHANGED -> kind == Kind.BLOCKS_CHANGED || kind == Kind.BLOCKSTATE_CHANGED;
                case REMOVED -> kind == Kind.BLOCKS_REMOVED;
                case INVENTORY -> kind == Kind.INVENTORIES_CHANGED;
            };
        }
    }

    private record ButtonListener(Type type, GuiLvcChangeViewer parent) implements IButtonActionListener
    {
        @Override
        public void actionPerformedWithButton(ButtonBase button, int mouseButton)
        {
            switch (this.type)
            {
                case START:
                    if (this.parent.verifier.isPaused())
                    {
                        this.parent.verifier.resume();
                        break;
                    }

                    if (LvcVerifierStartWorkflow.startIfGitmatica(this.parent.placement, this.parent, this.parent::initGui))
                    {
                        return;
                    }
                    break;
                case STOP:
                    this.parent.verifier.stopVerification();
                    break;
                case RESET_VERIFIER:
                    this.parent.verifier.reset();
                    break;
                case SET_LIST_TYPE:
                    this.parent.verifier.reset();
                    BlockInfoListType type = this.parent.placement.getSchematicVerifierType();
                    this.parent.placement.setSchematicVerifierType((BlockInfoListType) type.cycle(mouseButton == 0));
                    break;
                case RESET_IGNORED:
                    GitmaticaVerifiers.extension(this.parent.verifier).gitmatica$resetHiddenMismatches();
                    break;
                case TOGGLE_INFO_HUD:
                    SchematicVerifier verifier = this.parent.verifier;
                    verifier.toggleShouldRenderInfoHUD();

                    if (verifier.getShouldRenderText(RenderPhase.POST))
                    {
                        InfoHud.getInstance().addInfoHudRenderer(verifier, true);
                    }
                    else
                    {
                        InfoHud.getInstance().removeInfoHudRenderer(verifier, false);
                    }
                    break;
                case FILTER_ALL:
                    this.parent.setFilter(ChangeFilter.ALL);
                    break;
                case FILTER_ADDED:
                    this.parent.setFilter(ChangeFilter.ADDED);
                    break;
                case FILTER_CHANGED:
                    this.parent.setFilter(ChangeFilter.CHANGED);
                    break;
                case FILTER_REMOVED:
                    this.parent.setFilter(ChangeFilter.REMOVED);
                    break;
                case FILTER_INVENTORY:
                    this.parent.setFilter(ChangeFilter.INVENTORY);
                    break;
                case TOGGLE_OVERLAY_REVISION:
                    this.parent.switchOverlayRevision();
                    break;
            }

            this.parent.initGui();
        }

        enum Type
        {
            START,
            STOP,
            RESET_VERIFIER,
            SET_LIST_TYPE,
            RESET_IGNORED,
            TOGGLE_INFO_HUD,
            FILTER_ALL,
            FILTER_ADDED,
            FILTER_CHANGED,
            FILTER_REMOVED,
            FILTER_INVENTORY,
            TOGGLE_OVERLAY_REVISION
        }
    }
}
