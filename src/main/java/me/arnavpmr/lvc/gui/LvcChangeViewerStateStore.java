package me.arnavpmr.lvc.gui;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;

/**
 * Keeps transient Change Viewer state tied to the verifier that owns it.
 *
 * <p>The weak keys retain the existing lifetime semantics: closing the last
 * screen and releasing its verifier also releases the saved UI state.</p>
 */
final class LvcChangeViewerStateStore<K, E, G, F>
{
    private final Map<K, Set<E>> selections = new WeakHashMap<>();
    private final Map<K, Expansion<G>> expansions = new WeakHashMap<>();
    private final Map<K, F> filters = new WeakHashMap<>();
    private final Map<K, List<E>> inventoryPreviewSelections = new WeakHashMap<>();

    Set<E> selections(K owner)
    {
        return this.selections.get(owner);
    }

    void saveSelections(K owner, Set<E> selectedEntries)
    {
        this.selections.put(owner, Set.copyOf(selectedEntries));
    }

    Expansion<G> expansion(K owner)
    {
        return this.expansions.get(owner);
    }

    void saveExpansion(K owner, Map<?, Boolean> groups, Map<G, Boolean> kinds)
    {
        this.expansions.put(owner, new Expansion<>(groups, kinds));
    }

    F filter(K owner, F defaultFilter)
    {
        return this.filters.getOrDefault(owner, defaultFilter);
    }

    void saveFilter(K owner, F filter)
    {
        this.filters.put(owner, filter);
    }

    List<E> inventoryPreviewSelections(K owner)
    {
        return this.inventoryPreviewSelections.getOrDefault(owner, List.of());
    }

    void saveInventoryPreviewSelections(K owner, List<E> selectedEntries)
    {
        if (selectedEntries.isEmpty())
        {
            this.inventoryPreviewSelections.remove(owner);
        }
        else
        {
            this.inventoryPreviewSelections.put(owner, List.copyOf(selectedEntries));
        }
    }

    void clearInventoryPreviewSelections(K owner)
    {
        this.inventoryPreviewSelections.remove(owner);
    }

    record Expansion<G>(Map<?, Boolean> groups, Map<G, Boolean> kinds)
    {
        Expansion
        {
            groups = Map.copyOf(groups);
            kinds = Map.copyOf(kinds);
        }
    }
}
