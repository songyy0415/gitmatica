package me.arnavpmr.lvc.semantic;

import java.nio.file.Path;

public record LvcProjectCreationResult(Path repositoryDirectory, String commitId)
{
}
