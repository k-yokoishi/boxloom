package dev.boxloom.fabric;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import net.minecraft.SharedConstants;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.Bootstrap;

/** Exports vanilla IDs without starting Fabric, a game client, or a world. */
public final class RegistryExporter {
    public static void main(String[] args) throws IOException {
        if (args.length < 2 || args.length > 3
                || (args.length == 3 && !args[2].equals("--check"))) {
            throw new IllegalArgumentException("Usage: RegistryExporter <output> <minecraft-version> [--check]");
        }

        SharedConstants.tryDetectVersion();
        String version = SharedConstants.getCurrentVersion().name();
        if (!version.equals(args[1])) {
            throw new IllegalStateException("Expected Minecraft " + args[1] + ", loaded " + version);
        }
        Bootstrap.bootStrap();

        JsonObject snapshot = new JsonObject();
        snapshot.addProperty("minecraft_version", version);
        snapshot.add("blocks", identifiers(BuiltInRegistries.BLOCK));
        snapshot.add("entities", identifiers(BuiltInRegistries.ENTITY_TYPE));
        String content = new GsonBuilder().setPrettyPrinting().create().toJson(snapshot) + "\n";
        Path output = Path.of(args[0]);

        if (args.length == 3) {
            if (!Files.exists(output)
                    || !Files.readString(output, StandardCharsets.UTF_8).equals(content)) {
                throw new IllegalStateException(
                    "Registry snapshot is out of date. Run mise run python-generate-constants."
                );
            }
        } else {
            Files.createDirectories(output.toAbsolutePath().getParent());
            Files.writeString(output, content, StandardCharsets.UTF_8);
        }
    }

    private static JsonArray identifiers(Registry<?> registry) {
        JsonArray ids = new JsonArray();
        registry.keySet().stream()
            .filter(id -> id.getNamespace().equals("minecraft"))
            .map(Identifier::toString)
            .sorted()
            .forEach(ids::add);
        if (ids.isEmpty()) {
            throw new IllegalStateException("Registry is empty: " + registry.key());
        }
        return ids;
    }
}
