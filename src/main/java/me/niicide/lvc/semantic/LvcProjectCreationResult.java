package me.niicide.lvc.semantic;

import java.nio.file.Path;

public record LvcProjectCreationResult(Path repositoryDirectory, String commitId)
{
}
