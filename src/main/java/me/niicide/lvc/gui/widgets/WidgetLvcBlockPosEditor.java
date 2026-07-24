package me.niicide.lvc.gui.widgets;

import java.util.List;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.core.BlockPos;

import fi.dy.masa.litematica.gui.Icons;
import fi.dy.masa.malilib.gui.GuiBase;
import fi.dy.masa.malilib.gui.GuiTextFieldInteger;
import fi.dy.masa.malilib.gui.button.ButtonGeneric;
import fi.dy.masa.malilib.gui.interfaces.ITextFieldListener;
import fi.dy.masa.malilib.gui.widgets.WidgetBase;
import fi.dy.masa.malilib.gui.wrappers.TextFieldType;
import fi.dy.masa.malilib.gui.wrappers.TextFieldWrapper;
import fi.dy.masa.malilib.render.GuiContext;
import fi.dy.masa.malilib.util.KeyCodes;
import fi.dy.masa.malilib.util.StringUtils;
import me.niicide.lvc.gui.LvcGuiText;
import me.niicide.lvc.util.LvcGuiTextFields;

public class WidgetLvcBlockPosEditor extends WidgetBase
{
    public static final int DEFAULT_HEIGHT = 96;
    private static final int FIELD_X_OFFSET = 12;
    private static final int FIELD_WIDTH = 68;
    private static final int FIELD_HEIGHT = 16;
    private static final int BUTTON_GAP = 4;
    private static final int ROW_SPACING = 20;
    private static final int HEADER_HEIGHT = 14;
    private static final int TITLE_TEXT_OFFSET = 5;
    private static final int MOVE_BUTTON_OFFSET = ROW_SPACING * 2 + 22;
    private static final int BUTTON_HEIGHT = 20;
    private static final int NUDGE_BUTTON_WIDTH = 16;
    private static final String PLUS_MINUS_HOVER = "litematica.gui.button.hover.plus_minus_tip_ctrl_alt_shift";

    private final String title;
    private final ValueListener valueListener;
    private final Runnable invalidValueListener;
    private final Runnable moveToPlayerListener;
    private final List<CoordinateField> fields;
    private final ButtonGeneric moveToPlayerButton;

    public WidgetLvcBlockPosEditor(int x, int y, int width, String title, String moveToPlayerLabel,
                                   BlockPos initialValue, ValueListener valueListener,
                                   Runnable invalidValueListener, Runnable moveToPlayerListener)
    {
        super(x, y, width, DEFAULT_HEIGHT);
        this.title = title;
        this.valueListener = valueListener;
        this.invalidValueListener = invalidValueListener;
        this.moveToPlayerListener = moveToPlayerListener;
        this.fields = List.of(
                this.createField(Axis.X, 0),
                this.createField(Axis.Y, 1),
                this.createField(Axis.Z, 2)
        );
        this.moveToPlayerButton = new ButtonGeneric(
                x,
                this.getMoveButtonY(),
                FIELD_X_OFFSET + FIELD_WIDTH + BUTTON_GAP + NUDGE_BUTTON_WIDTH,
                BUTTON_HEIGHT,
                moveToPlayerLabel
        );
        this.moveToPlayerButton.setActionListener((button, mouseButton) -> this.moveToPlayerListener.run());
        this.setValue(initialValue);
    }

    public void setValue(BlockPos value)
    {
        this.fields.get(0).textField().setValueWrapper(String.valueOf(value.getX()));
        this.fields.get(1).textField().setValueWrapper(String.valueOf(value.getY()));
        this.fields.get(2).textField().setValueWrapper(String.valueOf(value.getZ()));
    }

    public void blurIfOutside(MouseButtonEvent click)
    {
        if (!this.isMouseOver((int) click.x(), (int) click.y()))
        {
            this.blurFields();
        }
    }

    @Override
    public void setPosition(int x, int y)
    {
        super.setPosition(x, y);

        for (int index = 0; index < this.fields.size(); index++)
        {
            CoordinateField field = this.fields.get(index);
            int fieldY = this.getFieldRowY(index) + 2;
            LvcGuiTextFields.setPosition(field.textField(), x + FIELD_X_OFFSET, fieldY);
            field.nudgeButton().setPosition(x + FIELD_X_OFFSET + FIELD_WIDTH + BUTTON_GAP, fieldY);
        }

        this.moveToPlayerButton.setPosition(x, this.getMoveButtonY());
    }

    @Override
    protected boolean onMouseClickedImpl(MouseButtonEvent click, boolean doubleClick)
    {
        boolean handled = false;

        for (CoordinateField field : this.fields)
        {
            if (field.nudgeButton().onMouseClicked(click, doubleClick))
            {
                return true;
            }

            handled |= field.wrapper().mouseClicked(click, doubleClick);
        }

        if (this.moveToPlayerButton.onMouseClicked(click, doubleClick))
        {
            return true;
        }

        return handled;
    }

    @Override
    public boolean onMouseDraggedImpl(MouseButtonEvent click, double dragXAmount, double dragYAmount)
    {
        for (CoordinateField field : this.fields)
        {
            if (field.wrapper().onMouseDragged(click, dragXAmount, dragYAmount))
            {
                return true;
            }
        }

        return false;
    }

    @Override
    public void onMouseReleasedImpl(MouseButtonEvent click)
    {
        for (CoordinateField field : this.fields)
        {
            field.nudgeButton().onMouseReleased(click);
        }

        this.moveToPlayerButton.onMouseReleased(click);
    }

    @Override
    protected boolean onKeyTypedImpl(KeyEvent input)
    {
        for (int index = 0; index < this.fields.size(); index++)
        {
            CoordinateField field = this.fields.get(index);

            if (!field.wrapper().isFocused())
            {
                continue;
            }

            if (input.key() == KeyCodes.KEY_TAB)
            {
                field.wrapper().setFocused(false);
                int direction = input.hasShiftDown() ? -1 : 1;
                int nextIndex = Math.floorMod(index + direction, this.fields.size());
                this.fields.get(nextIndex).wrapper().setFocused(true);
                return true;
            }

            return field.wrapper().onKeyTyped(input);
        }

        return false;
    }

    @Override
    protected boolean onCharTypedImpl(CharacterEvent input)
    {
        for (CoordinateField field : this.fields)
        {
            if (field.wrapper().onCharTyped(input))
            {
                return true;
            }
        }

        return false;
    }

    @Override
    public void render(GuiContext ctx, int mouseX, int mouseY, boolean selected)
    {
        super.render(ctx, mouseX, mouseY, selected);
        this.drawString(ctx, this.x, this.y + TITLE_TEXT_OFFSET, 0xFFFFFFFF,
                LvcGuiText.ellipsizeToWidth(this.title, this.width, this::getStringWidth));

        for (int index = 0; index < this.fields.size(); index++)
        {
            CoordinateField field = this.fields.get(index);
            int rowY = this.getFieldRowY(index);
            this.drawString(ctx, this.x, rowY + 5, 0xFFFFFFFF, field.axis().label());
            field.wrapper().draw(ctx, mouseX, mouseY);
            field.nudgeButton().render(ctx, mouseX, mouseY, false);
        }

        this.moveToPlayerButton.render(ctx, mouseX, mouseY, false);
    }

    @Override
    public void postRenderHovered(GuiContext ctx, int mouseX, int mouseY, boolean selected)
    {
        for (CoordinateField field : this.fields)
        {
            field.nudgeButton().postRenderHovered(ctx, mouseX, mouseY, false);
        }

        this.moveToPlayerButton.postRenderHovered(ctx, mouseX, mouseY, false);
    }

    private CoordinateField createField(Axis axis, int row)
    {
        int fieldY = this.getFieldRowY(row) + 2;
        GuiTextFieldInteger textField = new GuiTextFieldInteger(
                this.x + FIELD_X_OFFSET,
                fieldY,
                FIELD_WIDTH,
                FIELD_HEIGHT,
                this.textRenderer
        );
        TextFieldWrapper<GuiTextFieldInteger> wrapper = new TextFieldWrapper<>(
                textField,
                new CoordinateTextFieldListener(this),
                TextFieldType.INTEGER
        );
        ButtonGeneric nudgeButton = new ButtonGeneric(
                this.x + FIELD_X_OFFSET + FIELD_WIDTH + BUTTON_GAP,
                fieldY,
                Icons.BUTTON_PLUS_MINUS_16,
                StringUtils.translate(PLUS_MINUS_HOVER)
        );
        nudgeButton.setActionListener((button, mouseButton) -> this.nudge(axis, mouseButton));
        return new CoordinateField(axis, textField, wrapper, nudgeButton);
    }

    private boolean updateValueFromFields()
    {
        BlockPos value = this.parseValue();

        if (value == null)
        {
            this.invalidValueListener.run();
            return false;
        }

        return this.valueListener.update(value);
    }

    private void nudge(Axis axis, int mouseButton)
    {
        BlockPos value = this.parseValue();

        if (value == null)
        {
            this.invalidValueListener.run();
            return;
        }

        int amount = mouseButton == 1 ? -1 : 1;

        if (GuiBase.isCtrlDown())
        {
            amount *= 100;
        }

        if (GuiBase.isShiftDown())
        {
            amount *= 10;
        }

        if (GuiBase.isAltDown())
        {
            amount *= 5;
        }

        BlockPos updated = switch (axis)
        {
            case X -> value.offset(amount, 0, 0);
            case Y -> value.offset(0, amount, 0);
            case Z -> value.offset(0, 0, amount);
        };

        if (this.valueListener.update(updated))
        {
            this.setValue(updated);
        }
    }

    private BlockPos parseValue()
    {
        try
        {
            int x = Integer.parseInt(this.fields.get(0).textField().getValueWrapper().trim());
            int y = Integer.parseInt(this.fields.get(1).textField().getValueWrapper().trim());
            int z = Integer.parseInt(this.fields.get(2).textField().getValueWrapper().trim());
            return new BlockPos(x, y, z);
        }
        catch (NumberFormatException e)
        {
            return null;
        }
    }

    private void blurFields()
    {
        for (CoordinateField field : this.fields)
        {
            field.wrapper().setFocused(false);
        }
    }

    private int getFieldRowY(int row)
    {
        return this.y + HEADER_HEIGHT + row * ROW_SPACING;
    }

    private int getMoveButtonY()
    {
        return this.y + HEADER_HEIGHT + MOVE_BUTTON_OFFSET;
    }

    public interface ValueListener
    {
        boolean update(BlockPos value);
    }

    private enum Axis
    {
        X("X:"),
        Y("Y:"),
        Z("Z:");

        private final String label;

        Axis(String label)
        {
            this.label = label;
        }

        private String label()
        {
            return this.label;
        }
    }

    private record CoordinateField(Axis axis, GuiTextFieldInteger textField,
                                   TextFieldWrapper<GuiTextFieldInteger> wrapper,
                                   ButtonGeneric nudgeButton)
    {
    }

    private record CoordinateTextFieldListener(WidgetLvcBlockPosEditor widget)
            implements ITextFieldListener<GuiTextFieldInteger>
    {
        @Override
        public boolean onTextChange(GuiTextFieldInteger textField)
        {
            this.widget.updateValueFromFields();
            return false;
        }
    }
}
