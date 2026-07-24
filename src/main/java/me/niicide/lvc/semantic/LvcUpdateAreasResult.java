package me.niicide.lvc.semantic;

import javax.annotation.Nullable;
import org.eclipse.jgit.revwalk.RevCommit;

public record LvcUpdateAreasResult(@Nullable RevCommit commit, int regionCount)
{
}
