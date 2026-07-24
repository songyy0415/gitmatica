package me.niicide.lvc.gui;

import javax.annotation.Nullable;

import fi.dy.masa.litematica.gui.ButtonIcons;
import fi.dy.masa.malilib.gui.interfaces.IGuiIcon;
import fi.dy.masa.malilib.util.StringUtils;

enum GuiLvcProjectButtonType
{
    SAVE_VERSION("gitmatica.gui.button.lvc_project.save_version", null),
    DISCARD_CHANGES("gitmatica.gui.button.lvc_project.discard_changes", null),
    CLEAR_AREA("gitmatica.gui.button.lvc_project.clear_area", null),
    EXPORT("gitmatica.gui.button.lvc_project.export", null),
    PUSH("gitmatica.gui.button.lvc_project.push", null),
    PULL("gitmatica.gui.button.lvc_project.pull", null),
    BRANCH_SELECTOR("gitmatica.gui.button.lvc_project.branch", ButtonIcons.SCHEMATIC_PROJECTS),
    CHECKOUT_VERSION("gitmatica.gui.button.lvc_project.load", null),
    VIEW_CHANGES("gitmatica.gui.button.lvc_project.view_changes", null),
    VIEW_DIFFS("gitmatica.gui.button.lvc_project.view_diffs", null),
    REVERT_CHANGES("gitmatica.gui.button.lvc_project.revert_changes", null),
    PROJECT_EDITOR("gitmatica.gui.button.lvc_project.project_editor", ButtonIcons.AREA_EDITOR),
    PROJECT_SETTINGS("gitmatica.gui.button.lvc_project.project_settings", ButtonIcons.CONFIGURATION),
    CLOSE_PROJECT("gitmatica.gui.button.lvc_project.close_project", null),
    LITEMATICA_MENU("gitmatica.gui.button.lvc_project.litematica_menu", null);

    private final String translationKey;
    @Nullable private final IGuiIcon icon;

    GuiLvcProjectButtonType(String translationKey, @Nullable IGuiIcon icon)
    {
        this.translationKey = translationKey;
        this.icon = icon;
    }

    String getLabel(String checkoutBranchName)
    {
        if (this == BRANCH_SELECTOR)
        {
            return StringUtils.translate(this.translationKey, checkoutBranchName);
        }

        return StringUtils.translate(this.translationKey);
    }

    @Nullable
    IGuiIcon icon()
    {
        return this.icon;
    }
}
