/*
 * This source file is part of BetterModel.
 * Copyright (c) 2025 toxicity188
 * Licensed under the MIT License.
 * See LICENSE.md file for full license text.
 */

package kr.toxicity.model.api.bone;

import it.unimi.dsi.fastutil.objects.Object2ObjectLinkedOpenHashMap;
import kr.toxicity.model.api.util.InterpolationUtil;
import kr.toxicity.model.api.util.MathUtil;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Map;
import java.util.UUID;

import static kr.toxicity.model.api.util.CollectionUtil.newSequencedAddressingMap;

/**
 * Bone IK solver
 */
@ApiStatus.Internal
@RequiredArgsConstructor
public final class BoneIKSolver {

    private static final int MAX_IK_ITERATION = 20;
    private static final float SOLVE_DISTANCE_THRESHOLD = 1F / MathUtil.MODEL_TO_BLOCK_MULTIPLIER;
    private static final float MIN_ITERATION_CHANGE = 0.01F / MathUtil.MODEL_TO_BLOCK_MULTIPLIER;

    private final Map<UUID, RenderedBone> boneMap;
    private final Object2ObjectLinkedOpenHashMap<RenderedBone, IKChain> locators = newSequencedAddressingMap();

    /**
     * Adds some external locator to this solver
     * @param ikSource nullable source
     * @param ikTarget target bone
     * @param locator locator bone
     */
    public void addLocator(@Nullable UUID ikSource, @NotNull UUID ikTarget, @NotNull RenderedBone locator) {
        var target = boneMap.get(ikTarget);
        if (target == null) return;
        var source = ikSource == null ? locator.getParent() : boneMap.get(ikSource);
        if (source == null) return;
        var chain = new ArrayList<RenderedBone>();
        for (var current = target.getParent(); current != source && current != null; current = current.getParent()) {
            chain.add(current);
        }
        if (chain.isEmpty() && target.getParent() != source) return;
        if (target.getParent() != source && chain.getLast().getParent() != source) return;
        if (ikSource != null) {
            chain.add(source);
        }
        if (chain.isEmpty()) return;
        Collections.reverse(chain);
        var first = chain.getFirst();
        var endpoint = modelPosition(locator, first);
        var references = new Vector3f[chain.size()];
        for (int i = 0; i < chain.size(); i++) {
            references[i] = modelPosition(
                i + 1 < chain.size() ? chain.get(i + 1) : target,
                first
            ).sub(modelPosition(chain.get(i), first));
        }
        locators.put(locator, new IKChain(chain.toArray(RenderedBone[]::new), endpoint, references));
    }

    private static @NotNull Vector3f modelPosition(@NotNull RenderedBone bone, @NotNull RenderedBone reference) {
        return bone.restPosition()
            .add(bone.root.group.getPosition())
            .sub(reference.root.group.getPosition());
    }

    /**
     * Solves ik
     */
    public void solve() {
        solve(null);
    }

    /**
     * Solves ik
     * @param uuid player uuid
     */
    public void solve(@Nullable UUID uuid) {
        if (locators.isEmpty()) return;
        locators.object2ObjectEntrySet().fastForEach(entry -> {
            var locator = entry.getKey();
            var value = entry.getValue();
            fabrik(
                value.movements(uuid),
                value.cache.lengths,
                value.cache.bestPositions,
                value.cache.bestEndpoint,
                value.endpoint,
                value.references,
                locator.state(uuid).after().position().get(value.cache.destination)
                    .add(locator.root.group.getPosition())
                    .sub(value.first().root.group.getPosition())
            );
        });
    }

    private record IKChain(
        @NotNull RenderedBone[] bones,
        @NotNull Vector3f endpoint,
        @NotNull Vector3f[] references,
        @NotNull IKCache cache
    ) {

        private IKChain(
            @NotNull RenderedBone[] bones,
            @NotNull Vector3f endpoint,
            @NotNull Vector3f[] references
        ) {
            this(bones, endpoint, references, new IKCache(bones.length));
        }

        private @NotNull RenderedBone first() {
            return bones[0];
        }

        private @NotNull BoneMovement[] movements(@Nullable UUID uuid) {
            var movements = cache.movements;
            for (int i = 0; i < bones.length; i++) {
                movements[i] = bones[i].state(uuid).after();
            }
            return movements;
        }
    }

    private record IKCache(
        @NotNull BoneMovement[] movements,
        float[] lengths,
        @NotNull Vector3f destination,
        @NotNull Vector3f[] bestPositions,
        @NotNull Vector3f bestEndpoint
    ) {
        private IKCache(int length) {
            this(
                new BoneMovement[length],
                new float[length],
                new Vector3f(),
                java.util.stream.IntStream.range(0, length)
                    .mapToObj(i -> new Vector3f())
                    .toArray(Vector3f[]::new),
                new Vector3f()
            );
        }
    }

    private static void fabrik(
        @NotNull BoneMovement[] bones,
        float[] lengths,
        @NotNull Vector3f[] bestPositions,
        @NotNull Vector3f bestEndpoint,
        @NotNull Vector3f endpoint,
        @NotNull Vector3f[] references,
        @NotNull Vector3f target
    ) {
        var first = bones[0].position();

        var vecCache = new Vector3f();
        var rootPos = first.get(vecCache);

        for (int i = 0; i < bones.length - 1; i++) {
            var before = bones[i];
            var after = bones[i + 1];
            lengths[i] = before.position().distance(after.position());
        }
        var last = bones[bones.length - 1];
        lengths[bones.length - 1] = last.position().distance(endpoint);
        var bestDistance = Float.POSITIVE_INFINITY;
        var lastDistance = Float.POSITIVE_INFINITY;
        var solvedEndpoint = new Vector3f();
        for (int iter = 0; iter < MAX_IK_ITERATION; iter++) {
            // Forward
            solvedEndpoint.set(target);
            for (int i = bones.length - 1; i >= 0; i--) {
                var current = bones[i].position();
                var next = i + 1 < bones.length ? bones[i + 1].position() : solvedEndpoint;
                var dist = current.distanceSquared(next);
                if (dist < MathUtil.VECTOR_COMPARISON_EPSILON_SQ) continue;
                InterpolationUtil.lerp(next, current, lengths[i] / (float) Math.sqrt(dist), current);
            }
            // Backward
            first.set(rootPos);
            for (int i = 0; i < bones.length; i++) {
                var current = bones[i].position();
                var next = i + 1 < bones.length ? bones[i + 1].position() : solvedEndpoint;
                var dist = current.distanceSquared(next);
                if (dist < MathUtil.VECTOR_COMPARISON_EPSILON_SQ) continue;
                InterpolationUtil.lerp(current, next, lengths[i] / (float) Math.sqrt(dist), next);
            }
            var distance = solvedEndpoint.distance(target);
            if (distance < bestDistance) {
                bestDistance = distance;
                bestEndpoint.set(solvedEndpoint);
                for (int i = 0; i < bones.length; i++) {
                    bestPositions[i].set(bones[i].position());
                }
                if (distance <= SOLVE_DISTANCE_THRESHOLD) break;
            } else if (Math.abs(distance - lastDistance) < MIN_ITERATION_CHANGE) {
                break;
            }
            lastDistance = distance;
        }
        for (int i = 0; i < bones.length; i++) {
            bones[i].position().set(bestPositions[i]);
        }
        var rotCache = new Quaternionf();
        for (int i = 0; i < bones.length; i++) {
            var current = bones[i];

            var from = references[i];
            var to = (i + 1 < bones.length ? bones[i + 1].position() : bestEndpoint)
                .sub(current.position(), vecCache);
            if (from.lengthSquared() < MathUtil.VECTOR_COMPARISON_EPSILON_SQ
                || to.lengthSquared() < MathUtil.VECTOR_COMPARISON_EPSILON_SQ) continue;
            current.rotation().set(rotCache.identity().rotateTo(from.normalize(), to.normalize()).mul(current.rotation()));
        }
    }
}
