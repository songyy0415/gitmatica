package me.arnavpmr.lvc.gui;

import java.util.ArrayList;
import java.util.List;
import java.util.function.ToIntFunction;

public final class LvcGuiText
{
    private LvcGuiText()
    {
    }

    public static String ellipsizeToWidth(String text, int maxWidth, ToIntFunction<String> width)
    {
        if (maxWidth <= 0)
        {
            return "";
        }

        if (width.applyAsInt(text) <= maxWidth)
        {
            return text;
        }

        String suffix = "...";
        int suffixWidth = width.applyAsInt(suffix);

        if (suffixWidth > maxWidth)
        {
            for (int length = suffix.length() - 1; length > 0; length--)
            {
                String candidate = suffix.substring(0, length);

                if (width.applyAsInt(candidate) <= maxWidth)
                {
                    return candidate;
                }
            }

            return "";
        }

        for (int length = text.length(); length > 0; length--)
        {
            String candidate = text.substring(0, length);

            if (width.applyAsInt(candidate) + suffixWidth <= maxWidth)
            {
                return candidate + suffix;
            }
        }

        return suffix;
    }

    public static List<String> wrapTextToWidth(String text, int maxWidth, ToIntFunction<String> width)
    {
        return wrapTextToWidths(text, maxWidth, maxWidth, width);
    }

    public static List<String> wrapTextToWidths(String text, int firstLineWidth, int subsequentLineWidth,
                                                ToIntFunction<String> width)
    {
        List<String> lines = new ArrayList<>();
        String[] paragraphs = text.split("\\R", -1);

        for (String paragraph : paragraphs)
        {
            wrapParagraphToWidths(paragraph, firstLineWidth, subsequentLineWidth, width, lines);
        }

        return lines.isEmpty() ? List.of("") : lines;
    }

    private static void wrapParagraphToWidths(String paragraph, int firstLineWidth, int subsequentLineWidth,
                                              ToIntFunction<String> width, List<String> lines)
    {
        if (paragraph.isBlank())
        {
            lines.add("");
            return;
        }

        String line = "";

        for (String word : paragraph.trim().split("\\s+"))
        {
            int maxWidth = lines.isEmpty() ? firstLineWidth : subsequentLineWidth;
            String candidate = line.isEmpty() ? word : line + " " + word;

            if (width.applyAsInt(candidate) <= maxWidth)
            {
                line = candidate;
                continue;
            }

            if (!line.isEmpty())
            {
                lines.add(line);
                line = "";
                maxWidth = subsequentLineWidth;
            }

            if (width.applyAsInt(word) <= maxWidth)
            {
                line = word;
            }
            else
            {
                line = wrapLongWordToWidth(word, maxWidth, width, lines);
            }
        }

        if (!line.isEmpty())
        {
            lines.add(line);
        }
    }

    private static String wrapLongWordToWidth(String word, int maxWidth, ToIntFunction<String> width, List<String> lines)
    {
        StringBuilder builder = new StringBuilder();

        for (int i = 0; i < word.length(); i++)
        {
            String candidate = builder.toString() + word.charAt(i);

            if (width.applyAsInt(candidate) > maxWidth && !builder.isEmpty())
            {
                lines.add(builder.toString());
                builder.setLength(0);
            }

            builder.append(word.charAt(i));
        }

        return builder.toString();
    }
}
