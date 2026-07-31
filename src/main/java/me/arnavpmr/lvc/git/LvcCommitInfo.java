package me.arnavpmr.lvc.git;

public record LvcCommitInfo(String id, String shortId, String message, String description, String author, String time,
                            int subRegionCount, String changes)
{
}
