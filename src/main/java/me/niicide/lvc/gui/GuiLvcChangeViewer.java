package me.niicide.lvc.gui;

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
import java.util.WeakHashMap;
import javax.annotation.Nullable;
import net.minecraft.client.Minecraft;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.state.BlockState;
import fi.dy.masa.litematica.config.Configs;
import fi.dy.masa.litematica.config.Hotkeys;
import fi.dy.masa.litematica.data.DataManager;
import fi.dy.masa.litematica.gui.GuiMainMenu.ButtonListenerChangeMenu;
import fi.dy.masa.litematica.gui.Icons;
import fi.dy.masa.litematica.gui.widgets.WidgetSchematicVerificationResult;
import fi.dy.masa.litematica.render.infohud.InfoHud;
import fi.dy.masa.litematica.render.infohud.RenderPhase;
import fi.dy.masa.litematica.schematic.placement.SchematicPlacement;
import fi.dy.masa.litematica.schematic.verifier.SchematicVerifier;
import fi.dy.masa.litematica.schematic.verifier.SchematicVerifier.BlockMismatch;
import fi.dy.masa.litematica.schematic.verifier.SchematicVerifier.MismatchType;
import fi.dy.masa.litematica.schematic.verifier.SchematicVerifier.MismatchRenderPos;
import fi.dy.masa.litematica.schematic.verifier.SchematicVerifier.SortCriteria;
import fi.dy.masa.litematica.schematic.verifier.inventory.VerifierInventoryOverlay;
import fi.dy.masa.litematica.schematic.verifier.inventory.VerifierInventoryPreview;
import fi.dy.masa.litematica.util.BlockInfoListType;
import fi.dy.masa.litematica.util.ItemUtils;
import fi.dy.masa.malilib.gui.GuiBase;
import fi.dy.masa.malilib.gui.GuiListBase;
import fi.dy.masa.malilib.gui.Message.MessageType;
import fi.dy.masa.malilib.gui.button.ButtonBase;
import fi.dy.masa.malilib.gui.button.ButtonGeneric;
import fi.dy.masa.malilib.gui.button.IButtonActionListener;
import fi.dy.masa.malilib.gui.interfaces.ISelectionListener;
import fi.dy.masa.malilib.gui.widgets.WidgetListBase;
import fi.dy.masa.malilib.gui.widgets.WidgetListEntrySortable;
import fi.dy.masa.malilib.interfaces.ICompletionListener;
import fi.dy.masa.malilib.render.GuiContext;
import fi.dy.masa.malilib.render.RenderUtils;
import fi.dy.masa.malilib.util.GuiUtils;
import fi.dy.masa.malilib.util.InfoUtils;
import fi.dy.masa.malilib.util.StringUtils;
import fi.dy.masa.malilib.util.game.BlockUtils;
import me.niicide.lvc.LvcDiagnostics;
import me.niicide.lvc.LvcFriendlyErrors.Operation;
import me.niicide.lvc.LvcProjectService;
import me.niicide.lvc.diff.LvcSpatialDiffGroups.Group;
import me.niicide.lvc.diff.LvcSpatialDiffGroups.Kind;
import me.niicide.lvc.diff.LvcVerifierDiffGroups;
import me.niicide.lvc.diff.LvcVerifierDiffGroups.Entry;
import me.niicide.lvc.overlay.LvcTrackingOverlayService;
import me.niicide.lvc.overlay.LvcTrackingOverlayService.TrackingOverlayRevision;
import me.niicide.lvc.task.LvcOperationHandle;
import me.niicide.lvc.task.LvcRefreshMarker;
import me.niicide.lvc.task.LvcSemanticOverlayTask;
import me.niicide.lvc.task.LvcTaskCallbacks;
import me.niicide.lvc.task.LvcTaskRegistry;
import me.niicide.lvc.task.LvcTaskScheduling;

public class GuiLvcChangeViewer extends GuiListBase<GuiLvcChangeViewer.ChangeEntry, WidgetLvcChangeViewerEntry, WidgetListLvcChangeViewerEntries>
        implements ISelectionListener<GuiLvcChangeViewer.ChangeEntry>, ICompletionListener
{
    private static final String SWITCH_OVERLAY_OPERATION = "LVC Switch Change Overlay";
    private static final Map<SchematicVerifier, Set<ChangeEntry>> SAVED_SELECTIONS = new WeakHashMap<>();
    private static final Map<SchematicVerifier, ExpansionState> SAVED_EXPANSIONS = new WeakHashMap<>();
    private static final Map<SchematicVerifier, ChangeFilter> SAVED_FILTERS = new WeakHashMap<>();
    private static final Map<SchematicVerifier, List<ChangeEntry>> SAVED_INVENTORY_PREVIEW_SELECTIONS = new WeakHashMap<>();

    private final Path repositoryDirectory;
    private SchematicPlacement placement;
    private SchematicVerifier verifier;
    private final Map<BlockPos, Boolean> groupExpanded = new HashMap<>();
    private final Map<GroupKindKey, Boolean> kindExpanded = new HashMap<>();
    private ChangeFilter filter;
    private TrackingOverlayRevision overlayRevision;
    private boolean overlaySwitchInProgress;
    private boolean renderThroughFilterSynchronized;
    private List<Group<Entry>> spatialGroups = List.of();
    private final List<ChangeEntry> inventoryPreviewSelections = new ArrayList<>();

    public GuiLvcChangeViewer(SchematicPlacement placement)
    {
        super(10, 60);
        this.placement = placement;
        this.verifier = placement.getSchematicVerifier();
        this.repositoryDirectory = Objects.requireNonNull(
                LvcTrackingOverlayService.semanticTrackingRepositoryDirectory(placement.getSchematicFile()),
                "Gitmatica tracking overlay repository");
        this.overlayRevision = LvcTrackingOverlayService.trackingOverlayRevision(this.repositoryDirectory, placement);
        this.filter = SAVED_FILTERS.getOrDefault(this.verifier, ChangeFilter.ALL);
        this.inventoryPreviewSelections.addAll(
                SAVED_INVENTORY_PREVIEW_SELECTIONS.getOrDefault(this.verifier, List.of()));
        this.updateTitle();
        this.verifier.setLvcChangeInfoHud(true);
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

        label = StringUtils.translate("litematica.gui.title.lvc_project_manager");
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
            String str = StringUtils.translate("litematica.gui.label.lvc_change_viewer.status.blocks",
                    counts.added(), counts.removed(), counts.changedBlocks(), counts.changedStates());
            this.addLabel(12, y, 100, 12, 0xFFF0F0F0, str);
            str = StringUtils.translate("litematica.gui.label.lvc_change_viewer.status.inventories");
            this.addLabel(12, y + 14, 100, 12, 0xFFF0F0F0, str);
            int changedX = 12 + this.getStringWidth(str) + 4;
            str = StringUtils.translate("litematica.gui.label.lvc_change_viewer.status.inventories_changed",
                    counts.changedInventories());
            this.addLabel(changedX, y + 14, 100, 12, kindTextColor(Kind.INVENTORIES_CHANGED), str);
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
                label = StringUtils.translate("litematica.gui.button.lvc_change_viewer.reset_hidden");
                enabled = this.verifier.hasIgnoredStateMismatches();
                break;
            case TOGGLE_INFO_HUD:
                boolean val = InfoHud.getInstance().isEnabled() && this.verifier.getShouldRenderText(RenderPhase.POST);
                String str = (val ? TXT_GREEN : TXT_RED) + StringUtils.translate("litematica.message.value." + (val ? "on" : "off")) + TXT_RST;
                label = StringUtils.translate("litematica.gui.button.schematic_verifier.toggle_info_hud", str);
                break;
            case FILTER_ALL:
                label = StringUtils.translate("litematica.gui.button.lvc_change_viewer.filter_all");
                enabled = this.filter != ChangeFilter.ALL;
                break;
            case FILTER_ADDED:
                label = StringUtils.translate("litematica.gui.button.lvc_change_viewer.filter_added");
                enabled = this.filter != ChangeFilter.ADDED;
                break;
            case FILTER_CHANGED:
                label = StringUtils.translate("litematica.gui.button.lvc_change_viewer.filter_changed");
                enabled = this.filter != ChangeFilter.CHANGED;
                break;
            case FILTER_REMOVED:
                label = StringUtils.translate("litematica.gui.button.lvc_change_viewer.filter_removed");
                enabled = this.filter != ChangeFilter.REMOVED;
                break;
            case FILTER_INVENTORY:
                label = StringUtils.translate("litematica.gui.button.lvc_change_viewer.filter_inventory");
                enabled = this.filter != ChangeFilter.INVENTORY;
                break;
            case TOGGLE_OVERLAY_REVISION:
                String revision = StringUtils.translate(this.overlayRevision == TrackingOverlayRevision.CURRENT ?
                        "litematica.gui.label.lvc_change_viewer.overlay_current" :
                        "litematica.gui.label.lvc_change_viewer.overlay_parent");
                label = StringUtils.translate("litematica.gui.button.lvc_change_viewer.overlay_revision", revision);
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
        this.title = StringUtils.translate("litematica.gui.title.lvc_change_viewer", this.placement.getName());
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
            LvcGuiMessages.show(MessageType.ERROR, "litematica.error.lvc_project.no_world");
            return;
        }

        Optional<LvcOperationHandle> handle = LvcTaskRegistry.tryAcquireBackground(
                SWITCH_OVERLAY_OPERATION, this.repositoryDirectory);

        if (handle.isEmpty())
        {
            LvcGuiMessages.show(MessageType.ERROR, "litematica.error.lvc_project.operation_running",
                    LvcTaskRegistry.activeOperationName());
            return;
        }

        TrackingOverlayRevision targetRevision = this.overlayRevision == TrackingOverlayRevision.CURRENT ?
                TrackingOverlayRevision.PARENT : TrackingOverlayRevision.CURRENT;
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

    private void finishOverlaySwitch(LvcProjectService.TrackingOverlay overlay,
                                     TrackingOverlayRevision targetRevision)
    {
        SAVED_INVENTORY_PREVIEW_SELECTIONS.remove(this.verifier);
        this.placement = overlay.placement();
        this.verifier = overlay.verifier();
        this.overlayRevision = targetRevision;
        this.overlaySwitchInProgress = false;
        this.inventoryPreviewSelections.clear();
        this.spatialGroups = List.of();
        this.renderThroughFilterSynchronized = false;
        SAVED_FILTERS.put(this.verifier, this.filter);
        this.saveExpansionState();
        this.verifier.setLvcChangeInfoHud(true);
        this.updateTitle();
        this.clearRefreshMarkerAfterOverlaySwitch();
        this.reinitializeIfOpen();
    }

    private void failOverlaySwitch(Exception exception)
    {
        this.overlaySwitchInProgress = false;
        LvcGuiMessages.showTaskError(Operation.LOAD_OVERLAY,
                "litematica.error.lvc_project.tracking_failed", exception);
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

    public boolean isGroupExpanded(BlockPos groupAnchor)
    {
        return this.groupExpanded.getOrDefault(groupAnchor, true);
    }

    public boolean isKindExpanded(BlockPos groupAnchor, Kind kind)
    {
        return this.kindExpanded.getOrDefault(new GroupKindKey(groupAnchor, kind), false);
    }

    public boolean isKindVisible(Kind kind)
    {
        return this.filter.includes(kind);
    }

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
        SAVED_FILTERS.put(this.verifier, filter);
        this.syncSelectedMismatchPositions();
        this.syncRenderThroughMismatchFilter();
    }

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
                this.kindExpanded.putIfAbsent(new GroupKindKey(group.anchor(), kind), mismatchCount(group, kind) > 0);
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

    private static int mismatchCount(Group<Entry> group, Kind kind)
    {
        int count = 0;

        for (Entry entry : group.entries(kind))
        {
            count += entry.mismatch().count;
        }

        return count;
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
            count += mismatchCount(group, kind);
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
    public VerifierInventoryPreview getLockedInventoryPreview()
    {
        for (int index = this.inventoryPreviewSelections.size() - 1; index >= 0; --index)
        {
            VerifierInventoryPreview preview = this.inventoryPreviewSelections.get(index).inventoryPreview();

            if (preview != null)
            {
                return preview;
            }
        }

        return null;
    }

    public boolean hasLockedInventoryPreview()
    {
        return this.getLockedInventoryPreview() != null;
    }

    @Override
    public void onSelectionChange(@Nullable ChangeEntry entry)
    {
        if (entry == null)
        {
            return;
        }

        if (entry.type() == ChangeEntry.Type.GROUP || entry.type() == ChangeEntry.Type.KIND ||
            entry.type() == ChangeEntry.Type.DATA)
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

    private void updateInventoryPreviewSelection(ChangeEntry selectedEntry)
    {
        if (selectedEntry.inventoryPreview() != null)
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
        Set<ChangeEntry> selections = this.getListWidget().getSelectedEntries();
        this.inventoryPreviewSelections.removeIf(entry ->
                !selections.contains(entry) || !this.selectionStillExists(entry) || entry.inventoryPreview() == null);

        if (this.inventoryPreviewSelections.isEmpty())
        {
            SAVED_INVENTORY_PREVIEW_SELECTIONS.remove(this.verifier);
        }
        else
        {
            SAVED_INVENTORY_PREVIEW_SELECTIONS.put(this.verifier, List.copyOf(this.inventoryPreviewSelections));
        }
    }

    private void normalizeSelectionHierarchy(ChangeEntry selectedEntry)
    {
        Set<ChangeEntry> selections = this.getListWidget().getSelectedEntries();
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

    private void updateGroupDescendantSelections(ChangeEntry groupEntry, Set<ChangeEntry> selections, boolean selected)
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

            ChangeEntry kindEntry = ChangeEntry.kind(group, groupEntry.groupNumber(), kind);
            this.updateSelection(selections, kindEntry, selected);

            for (Entry data : group.entries(kind))
            {
                this.updateSelection(selections,
                        ChangeEntry.data(group, groupEntry.groupNumber(), kind, data), selected);
            }
        }
    }

    private void updateKindDescendantSelections(ChangeEntry kindEntry, Set<ChangeEntry> selections, boolean selected)
    {
        Group<Entry> group = this.groupFor(kindEntry.groupAnchor());

        if (group == null || kindEntry.kind() == null)
        {
            return;
        }

        for (Entry data : group.entries(kindEntry.kind()))
        {
            this.updateSelection(selections,
                    ChangeEntry.data(group, kindEntry.groupNumber(), kindEntry.kind(), data), selected);
        }
    }

    private void removeSelectedGroupAncestor(ChangeEntry entry, Set<ChangeEntry> selections)
    {
        selections.removeIf(candidate -> candidate.type() == ChangeEntry.Type.GROUP &&
                Objects.equals(candidate.groupAnchor(), entry.groupAnchor()));
    }

    private void removeSelectedKindAncestor(ChangeEntry entry, Set<ChangeEntry> selections)
    {
        selections.removeIf(candidate -> candidate.type() == ChangeEntry.Type.KIND &&
                candidate.kind() == entry.kind() &&
                Objects.equals(candidate.groupAnchor(), entry.groupAnchor()));
    }

    private void updateSelection(Set<ChangeEntry> selections, ChangeEntry entry, boolean selected)
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
        SAVED_SELECTIONS.put(this.verifier, Set.copyOf(this.getListWidget().getSelectedEntries()));
    }

    private void restoreSelections()
    {
        Set<ChangeEntry> saved = SAVED_SELECTIONS.get(this.verifier);

        if (saved == null)
        {
            return;
        }

        Set<ChangeEntry> restored = new HashSet<>();

        for (ChangeEntry entry : saved)
        {
            if (this.selectionStillExists(entry))
            {
                restored.add(entry);
            }
        }

        Set<ChangeEntry> selections = this.getListWidget().getSelectedEntries();
        selections.clear();
        selections.addAll(restored);
        this.pruneInventoryPreviewSelections();
        this.saveSelections();
        this.syncSelectedMismatchPositions();
    }

    private boolean selectionStillExists(ChangeEntry selection)
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

    void toggleExpanded(ChangeEntry entry)
    {
        if (entry.type() == ChangeEntry.Type.GROUP && entry.groupAnchor() != null)
        {
            this.groupExpanded.put(entry.groupAnchor(), !this.isGroupExpanded(entry.groupAnchor()));
            this.saveExpansionState();
            this.getListWidget().refreshEntries();
        }
        else if (entry.type() == ChangeEntry.Type.KIND && entry.groupAnchor() != null && entry.kind() != null)
        {
            GroupKindKey key = new GroupKindKey(entry.groupAnchor(), entry.kind());
            this.kindExpanded.put(key, !this.isKindExpanded(entry.groupAnchor(), entry.kind()));
            this.saveExpansionState();
            this.getListWidget().refreshEntries();
        }
    }

    private void restoreExpansionState()
    {
        ExpansionState saved = SAVED_EXPANSIONS.get(this.verifier);

        if (saved != null)
        {
            this.groupExpanded.putAll(saved.groups());
            this.kindExpanded.putAll(saved.kinds());
        }
    }

    private void saveExpansionState()
    {
        SAVED_EXPANSIONS.put(this.verifier, new ExpansionState(this.groupExpanded, this.kindExpanded));
    }

    private void syncSelectedMismatchPositions()
    {
        Map<SelectedPosition, MismatchRenderPos> positions = new LinkedHashMap<>();

        for (ChangeEntry selected : this.getListWidget().getSelectedEntries())
        {
            for (Entry data : this.entriesSelectedBy(selected))
            {
                for (BlockPos position : data.positions())
                {
                    MismatchType type = data.mismatch().mismatchType;
                    positions.putIfAbsent(new SelectedPosition(position, type), new MismatchRenderPos(type, position));
                }
            }
        }

        this.verifier.setMismatchPositionsSelected(positions.values());
    }

    private void syncRenderThroughMismatchFilter()
    {
        boolean changed;
        boolean needsInitialRebuild = this.renderThroughFilterSynchronized == false && this.filter != ChangeFilter.ALL;

        if (this.filter == ChangeFilter.ALL)
        {
            changed = this.verifier.clearRenderThroughMismatchFilter();
        }
        else
        {
            Map<SelectedPosition, MismatchRenderPos> positions = new LinkedHashMap<>();

            for (Group<Entry> group : this.spatialGroups)
            {
                for (Entry entry : group.allEntries(this::isKindVisible))
                {
                    for (BlockPos position : entry.positions())
                    {
                        MismatchType type = entry.mismatch().mismatchType;
                        positions.putIfAbsent(new SelectedPosition(position, type), new MismatchRenderPos(type, position));
                    }
                }
            }

            changed = this.verifier.setRenderThroughMismatchFilter(positions.values());
        }

        this.renderThroughFilterSynchronized = true;

        if (changed || needsInitialRebuild)
        {
            DataManager.getSchematicPlacementManager().markChunksForRebuild(this.placement);
        }
    }

    private List<Entry> entriesSelectedBy(ChangeEntry selected)
    {
        if (selected.type() == ChangeEntry.Type.DATA && selected.data() != null &&
            selected.kind() != null && this.isKindVisible(selected.kind()))
        {
            return List.of(selected.data());
        }

        Group<Entry> group = this.groupFor(selected.groupAnchor());

        if (group == null)
        {
            return List.of();
        }

        if (selected.type() == ChangeEntry.Type.KIND && selected.kind() != null && this.isKindVisible(selected.kind()))
        {
            return group.entries(selected.kind());
        }

        return selected.type() == ChangeEntry.Type.GROUP ? group.allEntries(this::isKindVisible) : List.of();
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
    protected ISelectionListener<ChangeEntry> getSelectionListener()
    {
        return this;
    }

    @Override
    protected WidgetListLvcChangeViewerEntries createListWidget(int listX, int listY)
    {
        return new WidgetListLvcChangeViewerEntries(listX, listY, this.getBrowserWidth(), this.getBrowserHeight(), this);
    }

    static String kindDisplayName(Kind kind)
    {
        String translationKey = switch (kind)
        {
            case INVENTORIES_CHANGED -> "litematica.gui.label.lvc_change_viewer.inventories";
            case BLOCKS_ADDED -> "litematica.gui.label.lvc_change_viewer.added";
            case BLOCKS_REMOVED -> "litematica.gui.label.lvc_change_viewer.removed";
            case BLOCKS_CHANGED -> "litematica.gui.label.lvc_change_viewer.changed_blocks";
            case BLOCKSTATE_CHANGED -> "litematica.gui.label.lvc_change_viewer.changed_states";
        };
        return StringUtils.translate(translationKey);
    }

    static int kindTextColor(Kind kind)
    {
        MismatchType mismatchType = switch (kind)
        {
            case INVENTORIES_CHANGED -> MismatchType.WRONG_INVENTORIES;
            case BLOCKS_ADDED -> MismatchType.EXTRA;
            case BLOCKS_REMOVED -> MismatchType.MISSING;
            case BLOCKS_CHANGED -> MismatchType.WRONG_BLOCK;
            case BLOCKSTATE_CHANGED -> MismatchType.WRONG_STATE;
        };
        return LvcTrackingOverlayService.semanticTrackingMismatchColor(mismatchType).intValue | 0xFF000000;
    }

    public record ChangeEntry(Type type, @Nullable BlockPos groupAnchor, int groupNumber, @Nullable Kind kind,
                            @Nullable Entry data, @Nullable String label, int count)
    {
        public static ChangeEntry group(Group<Entry> group, int groupNumber)
        {
            String label = StringUtils.translate("litematica.gui.label.lvc_change_viewer.group", groupNumber);
            return new ChangeEntry(Type.GROUP, group.anchor(), groupNumber, null, null, label, 0);
        }

        public static ChangeEntry kind(Group<Entry> group, int groupNumber, Kind kind)
        {
            return new ChangeEntry(Type.KIND, group.anchor(), groupNumber, kind, null,
                    kindDisplayName(kind), mismatchCount(group, kind));
        }

        public static ChangeEntry data(Group<Entry> group, int groupNumber, Kind kind, Entry data)
        {
            return new ChangeEntry(Type.DATA, group.anchor(), groupNumber, kind, data, null, data.mismatch().count);
        }

        public static ChangeEntry empty(String label)
        {
            return new ChangeEntry(Type.EMPTY, null, 0, null, null, label, 0);
        }

        public static ChangeEntry header()
        {
            return new ChangeEntry(Type.HEADER, null, 0, null, null, null, 0);
        }

        @Nullable
        public BlockMismatch mismatch()
        {
            return this.data != null ? this.data.mismatch() : null;
        }

        @Nullable
        public VerifierInventoryPreview inventoryPreview()
        {
            BlockMismatch mismatch = this.mismatch();
            return this.type == Type.DATA && this.kind == Kind.INVENTORIES_CHANGED && mismatch != null
                    ? mismatch.getInventoryPreview()
                    : null;
        }

        public enum Type
        {
            HEADER,
            GROUP,
            KIND,
            DATA,
            EMPTY
        }
    }

    private record GroupKindKey(BlockPos groupAnchor, Kind kind)
    {
    }

    private record ExpansionState(Map<BlockPos, Boolean> groups, Map<GroupKindKey, Boolean> kinds)
    {
        private ExpansionState
        {
            groups = Map.copyOf(groups);
            kinds = Map.copyOf(kinds);
        }
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

                    if (LvcVerifierStartWorkflow.startIfGitMatica(this.parent.placement, this.parent, this.parent::initGui))
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
                    this.parent.verifier.resetIgnoredStateMismatches();
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

class WidgetListLvcChangeViewerEntries extends WidgetListBase<GuiLvcChangeViewer.ChangeEntry, WidgetLvcChangeViewerEntry>
{
    private static int lastScrollbarPosition;
    private final GuiLvcChangeViewer parent;
    private boolean scrollbarRestored;

    WidgetListLvcChangeViewerEntries(int x, int y, int width, int height, GuiLvcChangeViewer parent)
    {
        super(x, y, width, height, parent);
        this.browserEntryHeight = 22;
        this.parent = parent;
        this.allowMultiSelection = true;
    }

    @Override
    public void drawContents(GuiContext ctx, int mouseX, int mouseY, float partialTicks)
    {
        super.drawContents(ctx, mouseX, mouseY, partialTicks);

        VerifierInventoryPreview preview = this.parent.getLockedInventoryPreview();

        if (preview == null)
        {
            preview = this.hoveredInventoryPreview(mouseX, mouseY);
        }

        if (preview != null)
        {
            VerifierInventoryOverlay.renderPreviewTooltip(ctx, preview, mouseX, mouseY,
                    "litematica.gui.label.lvc_change_viewer.before",
                    "litematica.gui.label.lvc_change_viewer.after");
        }

        lastScrollbarPosition = this.scrollBar.getValue();
    }

    @Nullable
    private VerifierInventoryPreview hoveredInventoryPreview(int mouseX, int mouseY)
    {
        for (WidgetLvcChangeViewerEntry widget : this.listWidgets)
        {
            VerifierInventoryPreview preview = widget.hoveredInventoryPreview(mouseX, mouseY);

            if (preview != null)
            {
                return preview;
            }
        }

        return null;
    }

    @Override
    protected boolean shouldRenderHoverStuff()
    {
        return !this.parent.hasLockedInventoryPreview();
    }

    @Override
    protected void offsetSelectionOrScrollbar(int amount, boolean changeSelection)
    {
        super.offsetSelectionOrScrollbar(amount, changeSelection);
        lastScrollbarPosition = this.scrollBar.getValue();
    }

    @Override
    protected WidgetLvcChangeViewerEntry createHeaderWidget(int x, int y, int listIndexStart, int usableHeight, int usedHeight)
    {
        int height = this.browserEntryHeight;

        if ((usedHeight + height) > usableHeight)
        {
            return null;
        }

        return this.createListEntryWidget(x, y, listIndexStart, true, GuiLvcChangeViewer.ChangeEntry.header());
    }

    @Override
    protected void refreshBrowserEntries()
    {
        this.listContents.clear();

        int groupNumber = 1;
        boolean hasVisibleGroups = false;

        for (Group<Entry> group : this.parent.spatialGroups())
        {
            if (this.parent.isGroupVisible(group))
            {
                this.addGroup(group, groupNumber);
                hasVisibleGroups = true;
            }

            groupNumber++;
        }

        if (!hasVisibleGroups)
        {
            this.listContents.add(GuiLvcChangeViewer.ChangeEntry.empty(
                    StringUtils.translate("litematica.gui.label.lvc_change_viewer.empty_category")));
        }

        this.reCreateListEntryWidgets();

        if (!this.scrollbarRestored && lastScrollbarPosition <= this.scrollBar.getMaxValue())
        {
            this.scrollBar.setValue(lastScrollbarPosition);
            this.scrollbarRestored = true;
            this.reCreateListEntryWidgets();
        }
    }

    private void addGroup(Group<Entry> group, int groupNumber)
    {
        this.listContents.add(GuiLvcChangeViewer.ChangeEntry.group(group, groupNumber));

        if (this.parent.isGroupExpanded(group.anchor()) == false)
        {
            return;
        }

        for (Kind kind : Kind.values())
        {
            if (!this.parent.isKindVisible(kind))
            {
                continue;
            }

            List<Entry> entries = group.entries(kind);

            if (entries.isEmpty())
            {
                continue;
            }

            this.listContents.add(GuiLvcChangeViewer.ChangeEntry.kind(group, groupNumber, kind));

            if (this.parent.isKindExpanded(group.anchor(), kind))
            {
                for (Entry entry : entries)
                {
                    this.listContents.add(GuiLvcChangeViewer.ChangeEntry.data(group, groupNumber, kind, entry));
                }
            }
        }
    }

    @Override
    protected WidgetLvcChangeViewerEntry createListEntryWidget(int x, int y, int listIndex, boolean isOdd, GuiLvcChangeViewer.ChangeEntry entry)
    {
        return new WidgetLvcChangeViewerEntry(x, y, this.browserEntryWidth, this.getBrowserEntryHeightFor(entry),
                isOdd, this, this.parent, entry, listIndex);
    }
}

class WidgetLvcChangeViewerEntry extends WidgetListEntrySortable<GuiLvcChangeViewer.ChangeEntry>
{
    private static final int DATA_INDENT = 44;
    private static final int GROUP_INDENT = 4;
    private static final int KIND_INDENT = 20;
    private static final int DISCLOSURE_ICON_WIDTH = Math.max(
            Icons.ARROW_DOWN.getWidth(), Icons.GITMATICA_ARROW_RIGHT.getWidth());
    private static final int DISCLOSURE_ICON_HEIGHT = Math.max(
            Icons.ARROW_DOWN.getHeight(), Icons.GITMATICA_ARROW_RIGHT.getHeight());
    private static final int DISCLOSURE_CLICK_PADDING = 4;
    private static final int DISCLOSURE_TEXT_GAP = 4;
    private static final int HEADER_OUTLINE_INSET = 3;
    private static final int STATE_COLUMN_PADDING = 40;
    private static final String HEADER_BEFORE = "litematica.gui.label.lvc_change_viewer.before";
    private static final String HEADER_AFTER = "litematica.gui.label.lvc_change_viewer.after";
    private static final String HEADER_COUNT = "litematica.gui.label.schematic_verifier.count";
    private static int maxNameLengthBefore;
    private static int maxNameLengthAfter;
    private static int maxCountLength;

    private final WidgetListLvcChangeViewerEntries listWidget;
    private final GuiLvcChangeViewer gui;
    private final SchematicVerifier verifier;
    private final GuiLvcChangeViewer.ChangeEntry entry;
    private final boolean isOdd;
    @Nullable private final ChangeMismatchInfo mismatchInfo;
    @Nullable private final ButtonGeneric buttonIgnore;

    WidgetLvcChangeViewerEntry(int x, int y, int width, int height, boolean isOdd,
                             WidgetListLvcChangeViewerEntries listWidget, GuiLvcChangeViewer gui,
                             GuiLvcChangeViewer.ChangeEntry entry, int listIndex)
    {
        super(x, y, width, height, entry, listIndex);
        this.columnCount = 3;
        this.listWidget = listWidget;
        this.gui = gui;
        this.verifier = gui.getPlacement().getSchematicVerifier();
        this.entry = entry;
        this.isOdd = isOdd;

        if (entry.type() == GuiLvcChangeViewer.ChangeEntry.Type.DATA && entry.mismatch() != null)
        {
            this.mismatchInfo = new ChangeMismatchInfo(entry.mismatch().stateExpected, entry.mismatch().stateFound);
            this.buttonIgnore = this.createButton(this.x + this.width, y + 1, ButtonListener.ButtonType.IGNORE_MISMATCH);
        }
        else
        {
            this.mismatchInfo = null;
            this.buttonIgnore = null;
        }
    }

    public static void setMaxNameLengths(List<BlockMismatch> mismatches)
    {
        maxNameLengthBefore = StringUtils.getStringWidth(GuiBase.TXT_BOLD + StringUtils.translate(HEADER_BEFORE) + GuiBase.TXT_RST);
        maxNameLengthAfter = StringUtils.getStringWidth(GuiBase.TXT_BOLD + StringUtils.translate(HEADER_AFTER) + GuiBase.TXT_RST);
        maxCountLength = 7 * StringUtils.getStringWidth("8");

        for (BlockMismatch mismatch : mismatches)
        {
            ItemStack stack = ItemUtils.getItemForState(mismatch.stateExpected);
            String name = WidgetSchematicVerificationResult.BlockMismatchInfo.getDisplayName(mismatch.stateExpected, stack);
            maxNameLengthBefore = Math.max(maxNameLengthBefore, StringUtils.getStringWidth(name));
            stack = ItemUtils.getItemForState(mismatch.stateFound);
            name = WidgetSchematicVerificationResult.BlockMismatchInfo.getDisplayName(mismatch.stateFound, stack);
            maxNameLengthAfter = Math.max(maxNameLengthAfter, StringUtils.getStringWidth(name));
        }

        maxCountLength = Math.max(maxCountLength, StringUtils.getStringWidth(GuiBase.TXT_BOLD + StringUtils.translate(HEADER_COUNT) + GuiBase.TXT_RST));
    }

    private ButtonGeneric createButton(int x, int y, ButtonListener.ButtonType type)
    {
        ButtonGeneric button = new ButtonGeneric(x, y, -1, true, type.getDisplayName());
        return this.addButton(button, new ButtonListener(type, this.entry, this.gui));
    }

    @Override
    protected int getCurrentSortColumn()
    {
        return this.verifier.getSortCriteria().ordinal();
    }

    @Override
    protected boolean getSortInReverse()
    {
        return this.verifier.getSortInReverse();
    }

    @Override
    protected int getColumnPosX(int column)
    {
        if (this.entry.type() == GuiLvcChangeViewer.ChangeEntry.Type.HEADER && column == 0)
        {
            return this.x + HEADER_OUTLINE_INSET;
        }

        int x1 = this.x + DATA_INDENT;
        int x2 = x1 + maxNameLengthBefore + STATE_COLUMN_PADDING;
        int x3 = x2 + maxNameLengthAfter + STATE_COLUMN_PADDING;

        return switch (column)
        {
            case 0 -> x1;
            case 1 -> x2;
            case 2 -> x3;
            case 3 -> x3 + maxCountLength + 20;
            default -> x1;
        };
    }

    @Override
    protected boolean onMouseClickedImpl(MouseButtonEvent click, boolean doubleClick)
    {
        if (click.input() == 0 && this.isOverExpansionArrow(click))
        {
            this.gui.toggleExpanded(this.entry);
            return true;
        }

        if (super.onMouseClickedImpl(click, doubleClick))
        {
            return true;
        }

        if (this.entry.type() != GuiLvcChangeViewer.ChangeEntry.Type.HEADER)
        {
            return false;
        }

        int column = this.getMouseOverColumn((int) click.x(), (int) click.y());

        switch (column)
        {
            case 0 -> this.verifier.setSortCriteria(SortCriteria.NAME_EXPECTED);
            case 1 -> this.verifier.setSortCriteria(SortCriteria.NAME_FOUND);
            case 2 -> this.verifier.setSortCriteria(SortCriteria.COUNT);
            default ->
            {
                return false;
            }
        }

        this.listWidget.refreshEntries();
        return true;
    }

    @Override
    public boolean canSelectAt(MouseButtonEvent click)
    {
        if (click.input() != 0 ||
            this.entry.type() == GuiLvcChangeViewer.ChangeEntry.Type.HEADER ||
            this.entry.type() == GuiLvcChangeViewer.ChangeEntry.Type.EMPTY ||
            this.isOverExpansionArrow(click))
        {
            return false;
        }

        return (this.buttonIgnore == null || click.x() < this.buttonIgnore.getX()) && super.canSelectAt(click);
    }

    @Override
    public void render(GuiContext ctx, int mouseX, int mouseY, boolean selected)
    {
        int color = 0xA0303030;

        if (selected)
        {
            color = 0xA0707070;
        }
        else if (this.isMouseOver(mouseX, mouseY))
        {
            color = 0xA0505050;
        }
        else if (this.isOdd)
        {
            color = 0xA0101010;
        }

        RenderUtils.drawRect(ctx, this.x, this.y, this.width, this.height, color);

        if (selected)
        {
            RenderUtils.drawOutline(ctx, this.x, this.y, this.width, this.height, 0xFFE0E0E0);
        }

        int x1 = this.getColumnPosX(0);
        int x2 = this.getColumnPosX(1);
        int x3 = this.getColumnPosX(2);
        int y = this.y + 7;

        switch (this.entry.type())
        {
            case HEADER ->
            {
                this.drawString(ctx, x1, y, 0xFFFFFFFF, GuiBase.TXT_BOLD + StringUtils.translate(HEADER_BEFORE) + GuiBase.TXT_RST);
                this.drawString(ctx, x2, y, 0xFFFFFFFF, GuiBase.TXT_BOLD + StringUtils.translate(HEADER_AFTER) + GuiBase.TXT_RST);
                this.drawString(ctx, x3, y, 0xFFFFFFFF, GuiBase.TXT_BOLD + StringUtils.translate(HEADER_COUNT) + GuiBase.TXT_RST);
                this.renderColumnHeader(ctx, mouseX, mouseY, Icons.ARROW_DOWN, Icons.ARROW_UP);
            }
            case GROUP -> this.renderExpandableRow(ctx, GROUP_INDENT, 0xFFFFFFFF, false, x3);
            case KIND -> this.renderExpandableRow(ctx, KIND_INDENT,
                    this.entry.kind() != null ? GuiLvcChangeViewer.kindTextColor(this.entry.kind()) : 0xFFFFFFFF, true, x3);
            case EMPTY -> this.drawString(ctx, this.x + DATA_INDENT + 18, y, 0xFFB0B0B0, Objects.toString(this.entry.label(), ""));
            case DATA -> this.renderDataRow(ctx, x1, x2, x3);
        }

        super.render(ctx, mouseX, mouseY, selected);
    }

    private boolean isExpanded()
    {
        if (this.entry.type() == GuiLvcChangeViewer.ChangeEntry.Type.GROUP && this.entry.groupAnchor() != null)
        {
            return this.gui.isGroupExpanded(this.entry.groupAnchor());
        }

        if (this.entry.type() == GuiLvcChangeViewer.ChangeEntry.Type.KIND &&
            this.entry.groupAnchor() != null && this.entry.kind() != null)
        {
            return this.gui.isKindExpanded(this.entry.groupAnchor(), this.entry.kind());
        }

        return false;
    }

    private void renderExpandableRow(GuiContext ctx, int indent, int color, boolean showCount, int countX)
    {
        Icons icon = this.isExpanded() ? Icons.ARROW_DOWN : Icons.GITMATICA_ARROW_RIGHT;
        int iconX = this.x + indent;
        int iconY = this.y + (this.height - icon.getHeight()) / 2;
        icon.renderAt(ctx, iconX, iconY, 0, true, false);
        this.drawString(ctx, this.x + indent + DISCLOSURE_ICON_WIDTH + DISCLOSURE_TEXT_GAP, this.y + 7, color,
                Objects.toString(this.entry.label(), ""));

        if (showCount)
        {
            this.drawString(ctx, countX, this.y + 7, 0xFFFFFFFF, String.valueOf(this.entry.count()));
        }
    }

    private boolean isOverExpansionArrow(MouseButtonEvent click)
    {
        int indent = switch (this.entry.type())
        {
            case GROUP -> GROUP_INDENT;
            case KIND -> KIND_INDENT;
            default -> -1;
        };

        if (indent < 0)
        {
            return false;
        }

        int iconX = this.x + indent;
        return click.x() >= iconX - DISCLOSURE_CLICK_PADDING &&
               click.x() < iconX + DISCLOSURE_ICON_WIDTH + DISCLOSURE_CLICK_PADDING &&
               click.y() >= this.y && click.y() < this.y + this.height;
    }

    private void renderDataRow(GuiContext ctx, int x1, int x2, int x3)
    {
        if (this.mismatchInfo == null || this.entry.mismatch() == null)
        {
            return;
        }

        int y = this.y + 7;
        this.drawString(ctx, x1 + 20, y, 0xFFFFFFFF, this.mismatchInfo.nameBefore);
        this.drawString(ctx, x2 + 20, y, 0xFFFFFFFF, this.mismatchInfo.nameAfter);
        this.drawString(ctx, x3, y, 0xFFFFFFFF, String.valueOf(this.entry.mismatch().count));

        y = this.y + 3;
        RenderUtils.drawRect(ctx, x1, y, 16, 16, 0x20FFFFFF);
        RenderUtils.drawRect(ctx, x2, y, 16, 16, 0x20FFFFFF);
        this.renderStateIcon(ctx, x1, y, this.mismatchInfo.stateBefore, this.mismatchInfo.stackBefore);
        this.renderStateIcon(ctx, x2, y, this.mismatchInfo.stateAfter, this.mismatchInfo.stackAfter);
    }

    private void renderStateIcon(GuiContext ctx, int x, int y, BlockState state, ItemStack stack)
    {
        boolean hasModel = state.getRenderShape() == RenderShape.MODEL;
        boolean isAirItem = stack.isEmpty();
        boolean useBlockModel = hasModel && (isAirItem || state.getBlock() == Blocks.FLOWER_POT);

        if (useBlockModel && fi.dy.masa.litematica.render.RenderUtils.stateModelHasQuads(state))
        {
            WidgetSchematicVerificationResult.renderModelInGui(ctx, x, y, 1, state);
        }
        else
        {
            ctx.renderItem(stack, x, y);
            ctx.renderItemDecorations(this.textRenderer, stack, x, y);
        }
    }

    @Override
    public void postRenderHovered(GuiContext ctx, int mouseX, int mouseY, boolean selected)
    {
        if (this.canShowInventoryPreview() && (this.buttonIgnore == null || mouseX < this.buttonIgnore.getX()))
        {
            return;
        }

        if (this.mismatchInfo != null && this.buttonIgnore != null && mouseX < this.buttonIgnore.getX())
        {
            ctx.pose().pushMatrix();
            ctx.pose().translate(0, 0);
            int x = mouseX + 10;
            int y = mouseY;
            int width = this.mismatchInfo.totalWidth();
            int height = this.mismatchInfo.totalHeight();

            if (x + width > GuiUtils.getCurrentScreenWidth())
            {
                x = mouseX - width - 10;
            }

            if (y + height > GuiUtils.getCurrentScreenHeight())
            {
                y = mouseY - height - 2;
            }

            this.mismatchInfo.render(ctx, x, y);
            ctx.pose().popMatrix();
        }
    }

    @Nullable
    VerifierInventoryPreview hoveredInventoryPreview(int mouseX, int mouseY)
    {
        if (!this.isMouseOver(mouseX, mouseY) ||
            (this.buttonIgnore != null && mouseX >= this.buttonIgnore.getX()))
        {
            return null;
        }

        return this.entry.inventoryPreview();
    }

    private boolean canShowInventoryPreview()
    {
        return this.entry.inventoryPreview() != null;
    }

    private record ChangeMismatchInfo(BlockState stateBefore, BlockState stateAfter, ItemStack stackBefore,
                                    ItemStack stackAfter, String registryBefore, String registryAfter,
                                    String nameBefore, String nameAfter, int totalWidth, int totalHeight,
                                    int columnWidthBefore)
    {
        ChangeMismatchInfo(BlockState stateBefore, BlockState stateAfter)
        {
            this(stateBefore, stateAfter, ItemUtils.getItemForState(stateBefore), ItemUtils.getItemForState(stateAfter));
        }

        private ChangeMismatchInfo(BlockState stateBefore, BlockState stateAfter, ItemStack stackBefore, ItemStack stackAfter)
        {
            this(stateBefore, stateAfter, stackBefore, stackAfter,
                    registryName(stateBefore), registryName(stateAfter),
                    WidgetSchematicVerificationResult.BlockMismatchInfo.getDisplayName(stateBefore, stackBefore),
                    WidgetSchematicVerificationResult.BlockMismatchInfo.getDisplayName(stateAfter, stackAfter));
        }

        private ChangeMismatchInfo(BlockState stateBefore, BlockState stateAfter, ItemStack stackBefore, ItemStack stackAfter,
                                 String registryBefore, String registryAfter, String nameBefore, String nameAfter)
        {
            this(stateBefore, stateAfter, stackBefore, stackAfter, registryBefore, registryAfter, nameBefore, nameAfter,
                    totalWidth(stateBefore, stateAfter, registryBefore, registryAfter, nameBefore, nameAfter),
                    totalHeight(stateBefore, stateAfter),
                    columnWidth(stateBefore, registryBefore, nameBefore));
        }

        private static String registryName(BlockState state)
        {
            Identifier id = BuiltInRegistries.BLOCK.getKey(state.getBlock());
            return id != null ? id.toString() : "<null>";
        }

        private static int columnWidth(BlockState state, String registryName, String displayName)
        {
            int width = Math.max(StringUtils.getStringWidth(displayName) + 20, StringUtils.getStringWidth(registryName));
            return Math.max(width, fi.dy.masa.litematica.render.RenderUtils.getMaxStringRenderLength(
                    BlockUtils.getFormattedBlockStateProperties(state, " = ")));
        }

        private static int totalWidth(BlockState before, BlockState after, String registryBefore, String registryAfter,
                                      String nameBefore, String nameAfter)
        {
            return columnWidth(before, registryBefore, nameBefore) + columnWidth(after, registryAfter, nameAfter) + 40;
        }

        private static int totalHeight(BlockState before, BlockState after)
        {
            List<String> propsBefore = BlockUtils.getFormattedBlockStateProperties(before, " = ");
            List<String> propsAfter = BlockUtils.getFormattedBlockStateProperties(after, " = ");
            return Math.max(propsBefore.size(), propsAfter.size()) * (StringUtils.getFontHeight() + 2) + 60;
        }

        void render(GuiContext ctx, int x, int y)
        {
            fi.dy.masa.litematica.render.RenderUtils.renderBackgroundMask(ctx, x + 1, y + 1, this.totalWidth - 1, this.totalHeight - 1);
            RenderUtils.drawOutlinedBox(ctx, x, y, this.totalWidth, this.totalHeight, 0xFF000000, GuiBase.COLOR_HORIZONTAL_BAR);
            int x1 = x + 10;
            int x2 = x + this.columnWidthBefore + 30;
            y += 4;
            String pre = GuiBase.TXT_WHITE + GuiBase.TXT_BOLD;
            ctx.drawString(ctx.fontRenderer(), pre + StringUtils.translate(HEADER_BEFORE) + GuiBase.TXT_RST, x1, y, 0xFFFFFFFF, false);
            ctx.drawString(ctx.fontRenderer(), pre + StringUtils.translate(HEADER_AFTER) + GuiBase.TXT_RST, x2, y, 0xFFFFFFFF, false);
            y += 12;
            RenderUtils.drawRect(ctx, x1, y, 16, 16, 0x50C0C0C0);
            RenderUtils.drawRect(ctx, x2, y, 16, 16, 0x50C0C0C0);
            int iconY = y;
            ctx.drawString(ctx.fontRenderer(), this.nameBefore, x1 + 20, y + 4, 0xFFFFFFFF, false);
            ctx.drawString(ctx.fontRenderer(), this.nameAfter, x2 + 20, y + 4, 0xFFFFFFFF, false);
            y += 20;
            ctx.drawString(ctx.fontRenderer(), this.registryBefore, x1, y, 0xFF4060FF, false);
            ctx.drawString(ctx.fontRenderer(), this.registryAfter, x2, y, 0xFF4060FF, false);
            y += StringUtils.getFontHeight() + 4;
            RenderUtils.renderText(ctx, x1, y, 0xFFB0B0B0,
                    BlockUtils.getFormattedBlockStateProperties(this.stateBefore, " = "));
            RenderUtils.renderText(ctx, x2, y, 0xFFB0B0B0,
                    BlockUtils.getFormattedBlockStateProperties(this.stateAfter, " = "));
            renderHoverIcon(ctx, x1, iconY, this.stateBefore, this.stackBefore);
            renderHoverIcon(ctx, x2, iconY, this.stateAfter, this.stackAfter);
        }

        private static void renderHoverIcon(GuiContext ctx, int x, int y, BlockState state, ItemStack stack)
        {
            boolean hasModel = state.getRenderShape() == RenderShape.MODEL;
            boolean isAirItem = stack.isEmpty();
            boolean useBlockModel = hasModel && (isAirItem || state.getBlock() == Blocks.FLOWER_POT);

            if (useBlockModel && fi.dy.masa.litematica.render.RenderUtils.stateModelHasQuads(state))
            {
                WidgetSchematicVerificationResult.renderModelInGui(ctx, x, y, 1, state);
            }
            else
            {
                ctx.renderItem(stack, x, y);
                ctx.renderItemDecorations(ctx.fontRenderer(), stack, x, y);
            }
        }
    }

    private record ButtonListener(ButtonType type, GuiLvcChangeViewer.ChangeEntry entry,
                                  GuiLvcChangeViewer gui) implements IButtonActionListener
    {
        @Override
        public void actionPerformedWithButton(ButtonBase button, int mouseButton)
        {
            if (this.type == ButtonType.IGNORE_MISMATCH && this.entry.mismatch() != null)
            {
                this.gui.getPlacement().getSchematicVerifier().ignoreStateMismatch(this.entry.mismatch());
                this.gui.initGui();
            }
        }

        enum ButtonType
        {
            IGNORE_MISMATCH("litematica.gui.button.lvc_change_viewer.hide");

            private final String translationKey;

            ButtonType(String translationKey)
            {
                this.translationKey = translationKey;
            }

            public String getDisplayName()
            {
                return StringUtils.translate(this.translationKey);
            }
        }
    }
}
