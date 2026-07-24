package me.niicide.lvc.semantic;

public record LvcSemanticScanMismatch(String chunkKey, String position, String expected, String actual)
{
    public String summary()
    {
        return "chunk " + this.chunkKey + " at " + this.position + ": expected " + this.expected + ", server " + this.actual;
    }
}
