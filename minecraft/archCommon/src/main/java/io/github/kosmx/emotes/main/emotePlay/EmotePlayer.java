package io.github.kosmx.emotes.main.emotePlay;

import dev.kosmx.playerAnim.api.TransformType;
import dev.kosmx.playerAnim.api.layered.KeyframeAnimationPlayer;
import dev.kosmx.playerAnim.core.data.KeyframeAnimation;
import dev.kosmx.playerAnim.core.data.opennbs.NBS;
import dev.kosmx.playerAnim.core.data.opennbs.SoundPlayer;
import dev.kosmx.playerAnim.core.data.opennbs.format.Layer;
import dev.kosmx.playerAnim.core.util.Vec3f;
import io.github.kosmx.emotes.executor.emotePlayer.IEmotePlayer;

import javax.annotation.Nullable;
import java.util.function.Consumer;

// modified keyframe animation player to play songs with animations
public abstract class EmotePlayer extends KeyframeAnimationPlayer implements IEmotePlayer {
    @Nullable
    final SoundPlayer song;

    /**
     * CobbleLegacy : vitesse de lecture (1 : normale), même principe que SpeedModifier de PlayerAnimator.
     * Le tick de l'animation avance de [speed] par tick du jeu ; l'interpolation suit la part restante.
     */
    private float speed = 1f;
    /** Part du tick du jeu déjà jouée par les images, et avance de l'animation en attente. */
    private float delta = 0f;
    private float shiftedDelta = 0f;
    private boolean scaled = false;

    /**
     *
     * @param emote emote to play
     * @param noteConsumer {@link Layer.Note} consumer
     * @param t begin playing from tick
     */
    public EmotePlayer(KeyframeAnimation emote, Consumer<Layer.Note> noteConsumer, int t) {
        super(emote, t);
        if (emote.extraData.containsKey("song")) {
            this.song = new SoundPlayer((NBS) emote.extraData.get("song"), noteConsumer, 0);
        }
        else {
            this.song = null;
        }

    }

    public float getSpeed() {
        return speed;
    }

    /** CobbleLegacy : change la vitesse en cours de lecture (aperçu de l'éditeur) ou au lancement. */
    public void setSpeed(float speed) {
        if (!Float.isFinite(speed) || speed <= 0f) speed = 1f;
        this.speed = speed;
        if (speed != 1f) this.scaled = true;
    }

    @Override
    public void tick() {
        if (!scaled) {
            tickOnce();
            return;
        }
        float step = 1f - this.delta;
        this.delta = 0f;
        step(step);
    }

    private void tickOnce() {
        super.tick();
        if (this.isActive()) {
            if (SoundPlayer.isPlayingSong(this.song)) song.tick();
        }
    }

    @Override
    public void setupAnim(float tickDelta) {
        if (!scaled) {
            super.setupAnim(tickDelta);
            return;
        }
        float step = tickDelta - this.delta;
        this.delta = tickDelta;
        step(step);
    }

    private void step(float step) {
        float progress = step * speed + shiftedDelta;
        while (progress > 1f) {
            progress -= 1f;
            tickOnce();
        }
        super.setupAnim(progress);
        this.shiftedDelta = progress;
    }

    @Override
    public Vec3f get3DTransform(String modelName, TransformType type, float tickDelta, Vec3f value0) {
        return super.get3DTransform(modelName, type, scaled ? shiftedDelta : tickDelta, value0);
    }
    /**
     * Is emotePlayer running
     *
     * @param emote EmotePlayer, can be null
     * @return is running
     */
    public static boolean isRunningEmote(@Nullable EmotePlayer emote) {
        return emote != null && emote.isActive();
    }

    @Override
    public boolean isRunning() {
        return isActive();
    }
}