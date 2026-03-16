/**
 * This source file is part of BetterModel.
 * Copyright (c) 2024–2026 toxicity188
 * Licensed under the MIT License.
 * See LICENSE.md file for full license text.
 */
package kr.toxicity.model.api.data.raw;

import com.google.gson.annotations.SerializedName;
import kr.toxicity.model.api.data.blueprint.BlueprintTexture;

import org.bukkit.Bukkit;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNull;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.Base64;
import javax.imageio.ImageIO;

/**
 * Represents a raw texture definition from a model file.
 * <p>
 * This record contains the texture's metadata and its content encoded as a Base64 string.
 * </p>
 *
 * @param name the name of the texture file (e.g., "texture.png")
 * @param source the Base64-encoded content of the texture image
 * @param width the width of the texture in pixels
 * @param height the height of the texture in pixels
 * @param uvWidth the UV width of the texture
 * @param uvHeight the UV height of the texture
 * @param frameTime the frame time of the texture
 * @param frameInterpolate the interpolation flag of the texture
 * @since 1.15.2
 */
@ApiStatus.Internal
public record ModelTexture(
    @NotNull String name,
    @NotNull String source,
    int width,
    int height,
    @SerializedName("uv_width") int uvWidth,
    @SerializedName("uv_height") int uvHeight,
    @SerializedName("frame_time") int frameTime,
    @SerializedName("frame_interpolate") boolean frameInterpolate
) {

    /**
     * Converts this raw texture into a processed {@link BlueprintTexture}.
     * <p>
     * This method decodes the Base64 source, generates a pack-compliant name, and determines if the texture should be included in the pack.
     * </p>
     *
     * @param context the model loading context
     * @return the blueprint texture
     * @since 1.15.2
     */
    public @NotNull BlueprintTexture toBlueprint(@NotNull ModelLoadContext context) {
        var decoded = context.trySupply(
            () -> {
                var sourceData = source();
                var commaIndex = sourceData.indexOf(',');
                var payload = commaIndex >= 0 ? sourceData.substring(commaIndex + 1) : sourceData;
                return Base64.getDecoder().decode(payload);
            },
            error -> new ModelLoadContext.Fallback<>(
                new byte[0],
                "Cannot decode texture '" + name() + "': " + error.getMessage()
            )
        );
        var resolution = resolveResolution(decoded, width(), height());
        var parsedName = context.placeholder.parseVariable(name());
        var nameIndex = parsedName.indexOf('.');
        return new BlueprintTexture(
            nameIndex >= 0 ? parsedName.substring(0, nameIndex) : parsedName,
            decoded,
            resolution[0],
            resolution[1],
            uvWidth(),
            uvHeight(),
            !name.startsWith("-"),
            frameTime(),
            frameInterpolate()
        );
    }

    private static int[] resolveResolution(byte[] imageData, int currentWidth, int currentHeight) {
        if (currentWidth > 0 && currentHeight > 0) {
            return new int[]{currentWidth, currentHeight};
        }
        try (var stream = new ByteArrayInputStream(imageData)) {
            BufferedImage image = ImageIO.read(stream);
            if (image != null) {
                var width = currentWidth > 0 ? currentWidth : image.getWidth();
                var height = currentHeight > 0 ? currentHeight : image.getHeight();
                return new int[]{width, height};
            }
        } catch (IOException ignored) {
            // Fallback to provided dimensions if image read fails
        }
        return new int[]{
                currentWidth > 0 ? currentWidth : 0,
                currentHeight > 0 ? currentHeight : 0
        };
    }

    /**
     * Returns the texture name without its file extension.
     *
     * @return the name without extension
     * @since 1.15.2
     */
    public @NotNull String nameWithoutExtension() {
        var name = name();
        var nameIndex = name.lastIndexOf('.');
        return nameIndex >= 0 ? name.substring(0, nameIndex) : name;
    }
}
