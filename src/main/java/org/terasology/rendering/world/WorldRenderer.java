/*
 * Copyright 2013 Moving Blocks
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.terasology.rendering.world;

import com.google.common.collect.Lists;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.Display;
import org.lwjgl.opengl.GL11;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.terasology.asset.Assets;
import org.terasology.audio.AudioManager;
import org.terasology.componentSystem.RenderSystem;
import org.terasology.config.Config;
import org.terasology.game.ComponentSystemManager;
import org.terasology.game.CoreRegistry;
import org.terasology.game.GameEngine;
import org.terasology.game.Timer;
import org.terasology.logic.characters.CharacterComponent;
import org.terasology.logic.manager.PathManager;
import org.terasology.logic.manager.PostProcessingRenderer;
import org.terasology.logic.manager.ShaderManager;
import org.terasology.logic.manager.WorldTimeEventManager;
import org.terasology.logic.players.LocalPlayer;
import org.terasology.logic.players.LocalPlayerSystem;
import org.terasology.math.AABB;
import org.terasology.math.Rect2i;
import org.terasology.math.Region3i;
import org.terasology.math.Vector3i;
import org.terasology.performanceMonitor.PerformanceMonitor;
import org.terasology.physics.BulletPhysics;
import org.terasology.rendering.AABBRenderer;
import org.terasology.rendering.cameras.Camera;
import org.terasology.rendering.cameras.DefaultCamera;
import org.terasology.rendering.logic.MeshRenderer;
import org.terasology.rendering.primitives.ChunkMesh;
import org.terasology.rendering.primitives.ChunkTessellator;
import org.terasology.rendering.shader.ShaderProgram;
import org.terasology.world.BlockEntityRegistry;
import org.terasology.world.ChunkView;
import org.terasology.world.EntityAwareWorldProvider;
import org.terasology.world.WorldBiomeProvider;
import org.terasology.world.WorldInfo;
import org.terasology.world.WorldProvider;
import org.terasology.world.WorldProviderCoreImpl;
import org.terasology.world.WorldProviderWrapper;
import org.terasology.world.WorldTimeEvent;
import org.terasology.world.block.Block;
import org.terasology.world.block.management.BlockManager;
import org.terasology.world.chunks.Chunk;
import org.terasology.world.chunks.ChunkProvider;

import javax.imageio.ImageIO;
import javax.vecmath.Vector3f;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedList;
import java.util.List;
import java.util.PriorityQueue;

import static org.lwjgl.opengl.GL11.GL_BLEND;
import static org.lwjgl.opengl.GL11.GL_DEPTH_BUFFER_BIT;
import static org.lwjgl.opengl.GL11.GL_FILL;
import static org.lwjgl.opengl.GL11.GL_FRONT_AND_BACK;
import static org.lwjgl.opengl.GL11.GL_LIGHT0;
import static org.lwjgl.opengl.GL11.GL_LINE;
import static org.lwjgl.opengl.GL11.GL_ONE_MINUS_SRC_ALPHA;
import static org.lwjgl.opengl.GL11.GL_SRC_ALPHA;
import static org.lwjgl.opengl.GL11.glBlendFunc;
import static org.lwjgl.opengl.GL11.glClear;
import static org.lwjgl.opengl.GL11.glColorMask;
import static org.lwjgl.opengl.GL11.glCullFace;
import static org.lwjgl.opengl.GL11.glDisable;
import static org.lwjgl.opengl.GL11.glEnable;
import static org.lwjgl.opengl.GL11.glLoadIdentity;
import static org.lwjgl.opengl.GL11.glPolygonMode;
import static org.lwjgl.opengl.GL11.glPopMatrix;
import static org.lwjgl.opengl.GL11.glPushMatrix;

/**
 * The world of Terasology. At its most basic the world contains chunks (consisting of a fixed amount of blocks)
 * and the player.
 * <p/>
 * The world is randomly generated by using a bunch of Perlin noise generators initialized
 * with a favored seed value.
 *
 * @author Benjamin Glatzel <benjamin.glatzel@me.com>
 */
public final class WorldRenderer {
    public static final int MAX_ANIMATED_CHUNKS = 64;
    public static final int MAX_BILLBOARD_CHUNKS = 64;
    public static final int VERTICAL_SEGMENTS = CoreRegistry.get(Config.class).getSystem().getVerticalChunkMeshSegments();

    private static final Logger logger = LoggerFactory.getLogger(WorldRenderer.class);

    /* WORLD PROVIDER */
    private final WorldProvider worldProvider;
    private ChunkProvider chunkProvider;

    /* PLAYER */
    private LocalPlayer player;

    /* CAMERA */
    public enum CAMERA_MODE {
        PLAYER,
        SPAWN
    }

    private CAMERA_MODE cameraMode = CAMERA_MODE.PLAYER;
    private Camera spawnCamera = new DefaultCamera();
    private DefaultCamera defaultCamera = new DefaultCamera();
    private Camera activeCamera = defaultCamera;

    /* CHUNKS */
    private ChunkTessellator chunkTesselator;
    private boolean pendingChunks = false;
    private final List<Chunk> chunksInProximity = Lists.newArrayList();
    private int chunkPosX, chunkPosZ;

    /* RENDERING */
    private final LinkedList<Chunk> renderQueueChunksOpaque = Lists.newLinkedList();
    private final PriorityQueue<Chunk> renderQueueChunksSortedWater = new PriorityQueue<Chunk>(16 * 16, new ChunkProximityComparator());
    private final PriorityQueue<Chunk> renderQueueChunksSortedBillboards = new PriorityQueue<Chunk>(16 * 16, new ChunkProximityComparator());

    /* HORIZON */
    private final Skysphere skysphere;

    /* TICKING */
    private Timer timer = CoreRegistry.get(Timer.class);
    private float tick = 0;
    private int tickTock = 0;
    private long lastTick;

    /* UPDATING */
    private final ChunkUpdateManager chunkUpdateManager;

    /* EVENTS */
    private final WorldTimeEventManager worldTimeEventManager;

    /* PHYSICS */
    private final BulletPhysics bulletPhysics;

    /* BLOCK GRID */
    private final BlockGrid blockGrid;

    /* STATISTICS */
    private int statDirtyChunks = 0, statVisibleChunks = 0, statIgnoredPhases = 0;
    private int statChunkMeshEmpty, statChunkNotReady, statRenderedTriangles;

    /* OTHER SETTINGS */
    private boolean wireframe;
    //for soundtrack
    private static short playround;

    private ComponentSystemManager systemManager;
    private AudioManager audioManager;

    private Config config = CoreRegistry.get(Config.class);

    /**
     * Initializes a new (local) world for the single player mode.
     *
     * @param worldInfo Information describing the world
     */
    public WorldRenderer(WorldInfo worldInfo, ChunkProvider chunkProvider, LocalPlayerSystem localPlayerSystem) {
        this.chunkProvider = chunkProvider;
        BlockManager blockManager = CoreRegistry.get(BlockManager.class);
        EntityAwareWorldProvider entityWorldProvider = new EntityAwareWorldProvider(new WorldProviderCoreImpl(worldInfo, this.chunkProvider, blockManager));
        CoreRegistry.put(BlockEntityRegistry.class, entityWorldProvider);
        CoreRegistry.get(ComponentSystemManager.class).register(entityWorldProvider, "engine:BlockEntityRegistry");
        worldProvider = new WorldProviderWrapper(entityWorldProvider);
        bulletPhysics = new BulletPhysics(worldProvider);
        chunkTesselator = new ChunkTessellator(worldProvider.getBiomeProvider());
        skysphere = new Skysphere(this);
        chunkUpdateManager = new ChunkUpdateManager(chunkTesselator, worldProvider);
        worldTimeEventManager = new WorldTimeEventManager(worldProvider);
        blockGrid = new BlockGrid();

        // TODO: won't need localPlayerSystem here once camera is in the ES proper
        localPlayerSystem.setPlayerCamera(defaultCamera);
        systemManager = CoreRegistry.get(ComponentSystemManager.class);
        audioManager = CoreRegistry.get(AudioManager.class);


        initTimeEvents();
    }

    /**
     * Updates the list of chunks around the player.
     *
     * @param force Forces the update
     * @return True if the list was changed
     */
    public boolean updateChunksInProximity(boolean force) {
        int newChunkPosX = calcCamChunkOffsetX();
        int newChunkPosZ = calcCamChunkOffsetZ();

        // TODO: This should actually be done based on events from the ChunkProvider on new chunk availability/old chunk removal
        int viewingDistance = config.getRendering().getActiveViewingDistance();

        if (chunkPosX != newChunkPosX || chunkPosZ != newChunkPosZ || force || pendingChunks) {
            // just add all visible chunks
            if (chunksInProximity.size() == 0 || force || pendingChunks) {
                chunksInProximity.clear();
                for (int x = -(viewingDistance / 2); x < viewingDistance / 2; x++) {
                    for (int z = -(viewingDistance / 2); z < viewingDistance / 2; z++) {
                        Chunk c = chunkProvider.getChunk(newChunkPosX + x, 0, newChunkPosZ + z);
                        if (c != null) {
                            chunksInProximity.add(c);
                        } else {
                            pendingChunks = true;
                        }
                    }
                }
            }
            // adjust proximity chunk list
            else {
                int vd2 = viewingDistance / 2;

                Rect2i oldView = new Rect2i(chunkPosX - vd2, chunkPosZ - vd2, viewingDistance, viewingDistance);
                Rect2i newView = new Rect2i(newChunkPosX - vd2, newChunkPosZ - vd2, viewingDistance, viewingDistance);

                // remove
                List<Rect2i> removeRects = Rect2i.subtractEqualsSized(oldView, newView);
                for (Rect2i r : removeRects) {
                    for (int x = r.minX(); x < r.maxX(); ++x) {
                        for (int y = r.minY(); y < r.maxY(); ++y) {
                            Chunk c = chunkProvider.getChunk(x, 0, y);
                            chunksInProximity.remove(c);
                        }
                    }
                }

                // add
                List<Rect2i> addRects = Rect2i.subtractEqualsSized(newView, oldView);
                for (Rect2i r : addRects) {
                    for (int x = r.minX(); x < r.maxX(); ++x) {
                        for (int y = r.minY(); y < r.maxY(); ++y) {
                            Chunk c = chunkProvider.getChunk(x, 0, y);
                            if (c != null) {
                                chunksInProximity.add(c);
                            } else {
                                pendingChunks = true;
                            }
                        }
                    }
                }
            }

            chunkPosX = newChunkPosX;
            chunkPosZ = newChunkPosZ;


            Collections.sort(chunksInProximity, new ChunkProximityComparator());

            return true;
        }

        return false;
    }

    private static class ChunkProximityComparator implements Comparator<Chunk> {

        @Override
        public int compare(Chunk o1, Chunk o2) {
            double distance = distanceToCamera(o1);
            double distance2 = distanceToCamera(o2);

            if (o1 == null) {
                return -1;
            } else if (o2 == null) {
                return 1;
            }

            if (distance == distance2)
                return 0;

            return distance2 > distance ? -1 : 1;
        }

        private float distanceToCamera(Chunk chunk) {
            Vector3f result = new Vector3f((chunk.getPos().x + 0.5f) * Chunk.SIZE_X, 0, (chunk.getPos().z + 0.5f) * Chunk.SIZE_Z);

            Vector3f cameraPos = CoreRegistry.get(WorldRenderer.class).getActiveCamera().getPosition();
            result.x -= cameraPos.x;
            result.z -= cameraPos.z;

            return result.length();
        }
    }

    private Vector3f getPlayerPosition() {
        if (player != null) {
            return player.getPosition();
        }
        return new Vector3f();
    }

    /**
     * Creates the world time events to play the game's soundtrack at specific times.
     */
    public void initTimeEvents() {

        // SUNRISE
        worldTimeEventManager.addWorldTimeEvent(new WorldTimeEvent(0.1, true) {
            @Override
            public void run() {
                if (getPlayerPosition().y < 50)
                    audioManager.playMusic(Assets.getMusic("engine:SpacialWinds"));
                else if (getPlayerPosition().y > 175)
                    audioManager.playMusic(Assets.getMusic("engine:Heaven"));
                else
                    audioManager.playMusic(Assets.getMusic("engine:Sunrise"));
            }
        });

        // AFTERNOON
        worldTimeEventManager.addWorldTimeEvent(new WorldTimeEvent(0.25, true) {
            @Override
            public void run() {
                //TODO get beter tck instead afternoon
                if (getPlayerPosition().y < 50)
                    audioManager.playMusic(Assets.getMusic("engine:DwarfForge"));
                else if (getPlayerPosition().y > 175)
                    audioManager.playMusic(Assets.getMusic("engine:SpaceExplorers"));
                else
                    audioManager.playMusic(Assets.getMusic("engine:Afternoon"));
            }
        });

        // SUNSET
        worldTimeEventManager.addWorldTimeEvent(new WorldTimeEvent(0.4, true) {
            @Override
            public void run() {
                if (getPlayerPosition().y < 50)
                    audioManager.playMusic(Assets.getMusic("engine:OrcFortress"));
                else if (getPlayerPosition().y > 175)
                    audioManager.playMusic(Assets.getMusic("engine:PeacefulWorld"));
                else
                    audioManager.playMusic(Assets.getMusic("engine:Sunset"));
            }
        });

        // NIGHT
        worldTimeEventManager.addWorldTimeEvent(new WorldTimeEvent(0.6, true) {
            @Override
            public void run() {
                if (getPlayerPosition().y < 50)
                    audioManager.playMusic(Assets.getMusic("engine:CreepyCaves"));
                else if (getPlayerPosition().y > 175)
                    audioManager.playMusic(Assets.getMusic("engine:ShootingStars"));
                else
                    audioManager.playMusic(Assets.getMusic("engine:Dimlight"));
            }
        });

        // NIGHT
        worldTimeEventManager.addWorldTimeEvent(new WorldTimeEvent(0.75, true) {
            @Override
            public void run() {
                if (getPlayerPosition().y < 50)
                    audioManager.playMusic(Assets.getMusic("engine:CreepyCaves"));
                else if (getPlayerPosition().y > 175)
                    audioManager.playMusic(Assets.getMusic("engine:NightTheme"));
                else
                    audioManager.playMusic(Assets.getMusic("engine:OtherSide"));
            }
        });

        // BEFORE SUNRISE
        worldTimeEventManager.addWorldTimeEvent(new WorldTimeEvent(0.9, true) {
            @Override
            public void run() {
                if (getPlayerPosition().y < 50)
                    audioManager.playMusic(Assets.getMusic("engine:CreepyCaves"));
                else if (getPlayerPosition().y > 175)
                    audioManager.playMusic(Assets.getMusic("engine:Heroes"));
                else
                    audioManager.playMusic(Assets.getMusic("engine:Resurface"));
            }
        });
    }

    /**
     * Updates the currently visible chunks (in sight of the player).
     */
    public void updateAndQueueVisibleChunks() {
        statDirtyChunks = 0;
        statVisibleChunks = 0;
        statIgnoredPhases = 0;

        for (int i = 0; i < chunksInProximity.size(); i++) {
            Chunk c = chunksInProximity.get(i);
            ChunkMesh[] mesh = c.getMesh();

            if (isChunkVisible(c) && isChunkValidForRender(c)) {

                if (triangleCount(mesh, ChunkMesh.RENDER_PHASE.OPAQUE) > 0)
                    renderQueueChunksOpaque.add(c);
                else
                    statIgnoredPhases++;

                if (triangleCount(mesh, ChunkMesh.RENDER_PHASE.WATER_AND_ICE) > 0)
                    renderQueueChunksSortedWater.add(c);
                else
                    statIgnoredPhases++;

                if (triangleCount(mesh, ChunkMesh.RENDER_PHASE.BILLBOARD_AND_TRANSLUCENT) > 0 && i < MAX_BILLBOARD_CHUNKS)
                    renderQueueChunksSortedBillboards.add(c);
                else
                    statIgnoredPhases++;

                if (i < MAX_ANIMATED_CHUNKS)
                    c.setAnimated(true);
                else
                    c.setAnimated(false);

                if (c.getPendingMesh() != null) {
                    for (int j = 0; j < c.getPendingMesh().length; j++) {
                        c.getPendingMesh()[j].generateVBOs();
                    }
                    if (c.getMesh() != null) {
                        for (int j = 0; j < c.getMesh().length; j++) {
                            c.getMesh()[j].dispose();
                        }
                    }
                    c.setMesh(c.getPendingMesh());
                    c.setPendingMesh(null);
                }

                if ((c.isDirty() || c.getMesh() == null) && isChunkValidForRender(c)) {
                    statDirtyChunks++;
                    chunkUpdateManager.queueChunkUpdate(c, ChunkUpdateManager.UPDATE_TYPE.DEFAULT);
                }

                statVisibleChunks++;
            } else if (i > config.getRendering().getMaxChunkVBOs()) {
                if (mesh != null) {
                    // Make sure not too many chunk VBOs are available in the video memory at the same time
                    // Otherwise VBOs are moved into system memory which is REALLY slow and causes lag
                    for (ChunkMesh m : mesh) {
                        m.dispose();
                    }
                    c.setMesh(null);
                }
            }
        }
    }

    private int triangleCount(ChunkMesh[] mesh, ChunkMesh.RENDER_PHASE type) {
        int count = 0;

        if (mesh != null)
            for (int i = 0; i < mesh.length; i++)
                count += mesh[i].triangleCount(type);

        return count;
    }

    private void resetStats() {
        statChunkMeshEmpty = 0;
        statChunkNotReady = 0;
        statRenderedTriangles = 0;
    }

    /**
     * Renders the world.
     */
    public void render() {
        resetStats();

        updateAndQueueVisibleChunks();

        PostProcessingRenderer.getInstance().beginRenderReflectedScene();
        glCullFace(GL11.GL_FRONT);
        getActiveCamera().setReflected(true);
        renderWorldReflection(getActiveCamera());
        getActiveCamera().setReflected(false);
        glCullFace(GL11.GL_BACK);
        PostProcessingRenderer.getInstance().endRenderReflectedScene();

        PostProcessingRenderer.getInstance().beginRenderScene();
        renderWorld(getActiveCamera());
        PostProcessingRenderer.getInstance().endRenderScene();

        /* RENDER THE FINAL POST-PROCESSED SCENE */
        PerformanceMonitor.startActivity("Render Post-Processing");
        PostProcessingRenderer.getInstance().renderScene();
        PerformanceMonitor.endActivity();

        if (cameraMode == CAMERA_MODE.PLAYER) {
            glClear(GL_DEPTH_BUFFER_BIT);
            glPushMatrix();
            glLoadIdentity();
            activeCamera.loadProjectionMatrix(90f);

            PerformanceMonitor.startActivity("Render First Person");
            for (RenderSystem renderer : systemManager.iterateRenderSubscribers()) {
                renderer.renderFirstPerson();
            }
            PerformanceMonitor.endActivity();

            glPopMatrix();
        }
    }

    public void renderWorld(Camera camera) {
        /* SKYSPHERE */
        PerformanceMonitor.startActivity("Render Sky");
        camera.lookThroughNormalized();
        skysphere.render();
        PerformanceMonitor.endActivity();

        /* WORLD RENDERING */
        PerformanceMonitor.startActivity("Render World");
        camera.lookThrough();

        glEnable(GL_LIGHT0);

        boolean headUnderWater = cameraMode == CAMERA_MODE.PLAYER && isUnderWater();

        if (wireframe)
            glPolygonMode(GL_FRONT_AND_BACK, GL_LINE);

        PerformanceMonitor.startActivity("Render Objects (Opaque)");

        for (RenderSystem renderer : systemManager.iterateRenderSubscribers()) {
            renderer.renderOpaque();
        }

        PerformanceMonitor.endActivity();

        PerformanceMonitor.startActivity("Render Chunks (Opaque)");

        /*
         * FIRST RENDER PASS: OPAQUE ELEMENTS
         */
        while (renderQueueChunksOpaque.size() > 0)
            renderChunk(renderQueueChunksOpaque.poll(), ChunkMesh.RENDER_PHASE.OPAQUE, camera);

        PerformanceMonitor.endActivity();

        PerformanceMonitor.startActivity("Render Chunks (Transparent)");

        /*
         * SECOND RENDER PASS: BILLBOARDS
         */
        glEnable(GL_BLEND);
        glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA);

        while (renderQueueChunksSortedBillboards.size() > 0)
            renderChunk(renderQueueChunksSortedBillboards.poll(), ChunkMesh.RENDER_PHASE.BILLBOARD_AND_TRANSLUCENT, camera);

        PerformanceMonitor.endActivity();

        PerformanceMonitor.startActivity("Render Objects (Transparent)");

        for (RenderSystem renderer : systemManager.iterateRenderSubscribers()) {
            renderer.renderTransparent();
        }

        PerformanceMonitor.endActivity();

        PerformanceMonitor.startActivity("Render Chunks (Water, Ice)");

        // Make sure the water surface is rendered if the player is swimming
        if (headUnderWater) {
            glDisable(GL11.GL_CULL_FACE);
        }

        /*
        * THIRD (AND FOURTH) RENDER PASS: WATER AND ICE
        */
        while (renderQueueChunksSortedWater.size() > 0) {
            Chunk c = renderQueueChunksSortedWater.poll();

            for (int j = 0; j < 2; j++) {

                if (j == 0) {
                    glColorMask(false, false, false, false);
                    renderChunk(c, ChunkMesh.RENDER_PHASE.WATER_AND_ICE, camera);
                } else {
                    glColorMask(true, true, true, true);
                    renderChunk(c, ChunkMesh.RENDER_PHASE.WATER_AND_ICE, camera);
                }
            }
        }

        PerformanceMonitor.endActivity();

        PerformanceMonitor.startActivity("Render Overlays");

        for (RenderSystem renderer : systemManager.iterateRenderSubscribers()) {
            renderer.renderOverlay();
        }

        PerformanceMonitor.endActivity();

        glDisable(GL_BLEND);

        if (headUnderWater)
            glEnable(GL11.GL_CULL_FACE);

        if (wireframe)
            glPolygonMode(GL_FRONT_AND_BACK, GL_FILL);

        glDisable(GL_LIGHT0);
    }

    public void renderWorldReflection(Camera camera) {
        PerformanceMonitor.startActivity("Render World (Reflection)");
        camera.lookThroughNormalized();
        skysphere.render();

        if (config.getRendering().isReflectiveWater()) {
            camera.lookThrough();

            glEnable(GL_LIGHT0);

            for (Chunk c : renderQueueChunksOpaque) {
                renderChunk(c, ChunkMesh.RENDER_PHASE.OPAQUE, camera);
            }

            glEnable(GL_BLEND);
            glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA);

            for (Chunk c : renderQueueChunksSortedBillboards) {
                renderChunk(c, ChunkMesh.RENDER_PHASE.BILLBOARD_AND_TRANSLUCENT, camera);
            }
            glDisable(GL_BLEND);
            glDisable(GL_LIGHT0);
        }

        PerformanceMonitor.endActivity();
    }

    private void renderChunk(Chunk chunk, ChunkMesh.RENDER_PHASE phase, Camera camera) {

        if (chunk.getChunkState() == Chunk.State.COMPLETE && chunk.getMesh() != null) {
            ShaderProgram shader = ShaderManager.getInstance().getShaderProgram("chunk");
            // Transfer the world offset of the chunk to the shader for various effects
            shader.setFloat3("chunkOffset", (float) (chunk.getPos().x * Chunk.SIZE_X), (float) (chunk.getPos().y * Chunk.SIZE_Y), (float) (chunk.getPos().z * Chunk.SIZE_Z));
            shader.setFloat("animated", chunk.getAnimated() ? 1.0f : 0.0f);
            shader.setFloat("clipHeight", camera.getClipHeight());

            GL11.glPushMatrix();

            Vector3f cameraPosition = camera.getPosition();
            GL11.glTranslated(chunk.getPos().x * Chunk.SIZE_X - cameraPosition.x, chunk.getPos().y * Chunk.SIZE_Y - cameraPosition.y, chunk.getPos().z * Chunk.SIZE_Z - cameraPosition.z);

            for (int i = 0; i < VERTICAL_SEGMENTS; i++) {
                if (!chunk.getMesh()[i].isEmpty()) {
                    if (config.getSystem().isRenderChunkBoundingBoxes()) {
                        AABBRenderer aabbRenderer = new AABBRenderer(chunk.getSubMeshAABB(i));
                        aabbRenderer.renderLocally(1f);
                        statRenderedTriangles += 12;
                    }

                    shader.enable();
                    chunk.getMesh()[i].render(phase);
                    statRenderedTriangles += chunk.getMesh()[i].triangleCount();
                }
            }

            GL11.glPopMatrix();
        } else {
            statChunkNotReady++;
        }
    }

    public float getRenderingLightValue() {
        return getRenderingLightValueAt(new Vector3f(getActiveCamera().getPosition()));
    }

    public float getRenderingLightValueAt(Vector3f pos) {
        float lightValueSun = (float) Math.pow(0.76, 16 - worldProvider.getSunlight(pos));
        lightValueSun *= getDaylight();
        float lightValueBlock = (float) Math.pow(0.76, 16 - worldProvider.getLight(pos));

        return (lightValueSun + lightValueBlock * (1.0f - lightValueSun)) + (1.0f - (float) getDaylight()) * 0.05f;
    }

    public void update(float delta) {
        PerformanceMonitor.startActivity("Cameras");
        animateSpawnCamera(delta);
        spawnCamera.update(delta);
        PerformanceMonitor.endActivity();

        PerformanceMonitor.startActivity("Update Tick");
        updateTick(delta);
        PerformanceMonitor.endActivity();

        // Free unused space
        PerformanceMonitor.startActivity("Update Chunk Cache");
        chunkProvider.update();
        PerformanceMonitor.endActivity();

        PerformanceMonitor.startActivity("Update Close Chunks");
        updateChunksInProximity(false);
        PerformanceMonitor.endActivity();

        PerformanceMonitor.startActivity("Skysphere");
        skysphere.update(delta);
        PerformanceMonitor.endActivity();

        if (activeCamera != null) {
            activeCamera.update(delta);
        }


        // And finally fire any active events
        PerformanceMonitor.startActivity("Fire Events");
        worldTimeEventManager.fireWorldTimeEvents();
        PerformanceMonitor.endActivity();

        PerformanceMonitor.startActivity("Physics Renderer");
        bulletPhysics.update(delta);
        PerformanceMonitor.endActivity();
    }

    public boolean isUnderWater() {
        Vector3f cameraPos = CoreRegistry.get(WorldRenderer.class).getActiveCamera().getPosition();
        Block block = CoreRegistry.get(WorldProvider.class).getBlock(new Vector3f(cameraPos));
        return block.isLiquid();
    }

    private void animateSpawnCamera(double delta) {
        if (player == null || !player.isValid())
            return;
        CharacterComponent player = this.player.getCharacterEntity().getComponent(CharacterComponent.class);

        Vector3f cameraPosition = new Vector3f(player.spawnPosition);
        cameraPosition.y += 32;
        cameraPosition.x += Math.sin(getTick() * 0.0005f) * 32f;
        cameraPosition.z += Math.cos(getTick() * 0.0005f) * 32f;

        Vector3f playerToCamera = new Vector3f();
        playerToCamera.sub(getPlayerPosition(), cameraPosition);
        double distanceToPlayer = playerToCamera.length();

        Vector3f cameraDirection = new Vector3f();

        if (distanceToPlayer > 64.0) {
            cameraDirection.sub(player.spawnPosition, cameraPosition);
        } else {
            cameraDirection.set(playerToCamera);
        }

        cameraDirection.normalize();

        spawnCamera.getPosition().set(cameraPosition);
        spawnCamera.getViewingDirection().set(cameraDirection);
    }

    /**
     * Updates the tick variable that animation is based on
     */
    private void updateTick(float delta) {
        tick += delta * 1000;
    }

    /**
     * Returns the maximum height at a given position.
     *
     * @param x The X-coordinate
     * @param z The Z-coordinate
     * @return The maximum height
     */
    public final int maxHeightAt(int x, int z) {
        for (int y = Chunk.SIZE_Y - 1; y >= 0; y--) {
            if (worldProvider.getBlock(x, y, z).getId() != 0x0)
                return y;
        }

        return 0;
    }

    /**
     * Chunk position of the player.
     *
     * @return The player offset on the x-axis
     */
    private int calcCamChunkOffsetX() {
        return (int) (getActiveCamera().getPosition().x / Chunk.SIZE_X);
    }

    /**
     * Chunk position of the player.
     *
     * @return The player offset on the z-axis
     */
    private int calcCamChunkOffsetZ() {
        return (int) (getActiveCamera().getPosition().z / Chunk.SIZE_Z);
    }

    /**
     * Sets a new player and spawns him at the spawning point.
     *
     * @param p The player
     */
    public void setPlayer(LocalPlayer p) {
        player = p;
        updateChunksInProximity(true);
    }

    public void changeViewDistance(int viewingDistance) {
        logger.debug("New Viewing Distance: {}", viewingDistance);
        updateChunksInProximity(true);
    }

    public ChunkProvider getChunkProvider() {
        return chunkProvider;
    }

    /**
     * Disposes this world.
     */
    public void dispose() {
        worldProvider.dispose();
        WorldInfo worldInfo = worldProvider.getWorldInfo();
        try {
            WorldInfo.save(new File(PathManager.getInstance().getWorldSavePath(worldInfo.getTitle()), WorldInfo.DEFAULT_FILE_NAME), worldInfo);
        } catch (IOException e) {
            logger.error("Failed to save world manifest", e);
        }

        audioManager.stopAllSounds();
    }

    /**
     * @return true if pregeneration is complete
     */
    public boolean pregenerateChunks() {
        boolean complete = true;
        int newChunkPosX = calcCamChunkOffsetX();
        int newChunkPosZ = calcCamChunkOffsetZ();
        int viewingDistance = config.getRendering().getActiveViewingDistance();

        chunkProvider.update();
        for (Vector3i pos : Region3i.createFromCenterExtents(new Vector3i(newChunkPosX, 0, newChunkPosZ), new Vector3i(viewingDistance / 2, 0, viewingDistance / 2))) {
            Chunk chunk = chunkProvider.getChunk(pos);
            if (chunk == null) {
                complete = false;
                continue;
            } else if (chunk.isDirty()) {
                ChunkView view = worldProvider.getLocalView(chunk.getPos());
                if (view == null) {
                    continue;
                }
                chunk.setDirty(false);

                ChunkMesh[] newMeshes = new ChunkMesh[VERTICAL_SEGMENTS];
                for (int seg = 0; seg < VERTICAL_SEGMENTS; seg++) {
                    newMeshes[seg] = chunkTesselator.generateMesh(view, chunk.getPos(), Chunk.SIZE_Y / VERTICAL_SEGMENTS, seg * (Chunk.SIZE_Y / VERTICAL_SEGMENTS));
                }

                chunk.setPendingMesh(newMeshes);

                if (chunk.getPendingMesh() != null) {

                    for (int j = 0; j < chunk.getPendingMesh().length; j++) {
                        chunk.getPendingMesh()[j].generateVBOs();
                    }
                    if (chunk.getMesh() != null) {
                        for (int j = 0; j < chunk.getMesh().length; j++) {
                            chunk.getMesh()[j].dispose();
                        }
                    }
                    chunk.setMesh(chunk.getPendingMesh());
                    chunk.setPendingMesh(null);
                }
                return false;
            }
        }
        return complete;
    }

    public void printScreen() {
        GL11.glReadBuffer(GL11.GL_FRONT);
        final int width = Display.getWidth();
        final int height = Display.getHeight();
        //int bpp = Display.getDisplayMode().getBitsPerPixel(); does return 0 - why?
        final int bpp = 4;
        final ByteBuffer buffer = BufferUtils.createByteBuffer(width * height * bpp); // hardcoded until i know how to get bpp
        GL11.glReadPixels(0, 0, width, height, (bpp == 3) ? GL11.GL_RGB : GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, buffer);
        Runnable r = new Runnable() {
            @Override
            public void run() {
                Calendar cal = Calendar.getInstance();
                SimpleDateFormat sdf = new SimpleDateFormat("yyMMddHHmmssSSS");

                File file = new File(PathManager.getInstance().getScreensPath(), sdf.format(cal.getTime()) + ".png");
                BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);

                for (int x = 0; x < width; x++)
                    for (int y = 0; y < height; y++) {
                        int i = (x + width * y) * bpp;
                        int r = buffer.get(i) & 0xFF;
                        int g = buffer.get(i + 1) & 0xFF;
                        int b = buffer.get(i + 2) & 0xFF;
                        image.setRGB(x, height - (y + 1), (0xFF << 24) | (r << 16) | (g << 8) | b);
                    }

                try {
                    ImageIO.write(image, "png", file);
                } catch (IOException e) {
                    logger.warn("Could not save screenshot!", e);
                }
            }
        };

        CoreRegistry.get(GameEngine.class).submitTask("Write screenshot", r);
    }


    @Override
    public String toString() {
        return String.format("world (numdropped: %d, biome: %s, time: %.2f, exposure: %.2f, sun: %.2f, cache: %fMb, dirty: %d, ign: %d, vis: %d, tri: %d, empty: %d, !ready: %d, seed: \"%s\", title: \"%s\")", ((MeshRenderer) CoreRegistry.get(ComponentSystemManager.class).get("engine:MeshRenderer")).lastRendered, getPlayerBiome(), worldProvider.getTimeInDays(), PostProcessingRenderer.getInstance().getExposure(), skysphere.getSunPosAngle(), chunkProvider.size(), statDirtyChunks, statIgnoredPhases, statVisibleChunks, statRenderedTriangles, statChunkMeshEmpty, statChunkNotReady, worldProvider.getSeed(), worldProvider.getTitle());
    }

    public LocalPlayer getPlayer() {
        return player;
    }

    public boolean isAABBVisible(AABB aabb) {
        return getActiveCamera().getViewFrustum().intersects(aabb);
    }

    public boolean isChunkValidForRender(Chunk c) {
        return worldProvider.getLocalView(c.getPos()) != null;
    }

    public boolean isChunkVisible(Chunk c) {
        return getActiveCamera().getViewFrustum().intersects(c.getAABB());
    }

    public double getDaylight() {
        return skysphere.getDaylight();
    }

    public WorldBiomeProvider.Biome getPlayerBiome() {
        Vector3f pos = getPlayerPosition();
        return worldProvider.getBiomeProvider().getBiomeAt(pos.x, pos.z);
    }

    public WorldProvider getWorldProvider() {
        return worldProvider;
    }

    public BlockGrid getBlockGrid() {
        return blockGrid;
    }

    public Skysphere getSkysphere() {
        return skysphere;
    }

    public double getTick() {
        return tick;
    }

    public List<Chunk> getChunksInProximity() {
        return chunksInProximity;
    }

    public boolean isWireframe() {
        return wireframe;
    }

    public void setWireframe(boolean _wireframe) {
        this.wireframe = _wireframe;
    }

    public BulletPhysics getBulletRenderer() {
        return bulletPhysics;
    }

    public Camera getActiveCamera() {
        return activeCamera;
    }

    //TODO: Review
    public void setCameraMode(CAMERA_MODE mode) {
        cameraMode = mode;
        switch (mode) {
            case PLAYER:
                activeCamera = defaultCamera;
                break;
            default:
                activeCamera = spawnCamera;
                break;
        }
    }

    public ChunkTessellator getChunkTesselator() {
        return chunkTesselator;
    }
}
