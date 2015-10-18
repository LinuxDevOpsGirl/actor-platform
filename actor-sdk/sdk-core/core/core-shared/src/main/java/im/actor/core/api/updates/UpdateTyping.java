package im.actor.core.api.updates;
/*
 *  Generated by the Actor API Scheme generator.  DO NOT EDIT!
 */

import im.actor.runtime.bser.*;
import im.actor.runtime.collections.*;
import static im.actor.runtime.bser.Utils.*;
import im.actor.core.network.parser.*;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.NotNull;
import com.google.j2objc.annotations.ObjectiveCName;
import java.io.IOException;
import java.util.List;
import java.util.ArrayList;
import im.actor.core.api.*;

public class UpdateTyping extends Update {

    public static final int HEADER = 0x6;
    public static UpdateTyping fromBytes(byte[] data) throws IOException {
        return Bser.parse(new UpdateTyping(), data);
    }

    private ApiPeer peer;
    private int uid;
    private ApiTypingType typingType;

    public UpdateTyping(@NotNull ApiPeer peer, int uid, @NotNull ApiTypingType typingType) {
        this.peer = peer;
        this.uid = uid;
        this.typingType = typingType;
    }

    public UpdateTyping() {

    }

    @NotNull
    public ApiPeer getPeer() {
        return this.peer;
    }

    public int getUid() {
        return this.uid;
    }

    @NotNull
    public ApiTypingType getTypingType() {
        return this.typingType;
    }

    @Override
    public void parse(BserValues values) throws IOException {
        this.peer = values.getObj(1, new ApiPeer());
        this.uid = values.getInt(2);
        this.typingType = ApiTypingType.parse(values.getInt(3));
    }

    @Override
    public void serialize(BserWriter writer) throws IOException {
        if (this.peer == null) {
            throw new IOException();
        }
        writer.writeObject(1, this.peer);
        writer.writeInt(2, this.uid);
        if (this.typingType == null) {
            throw new IOException();
        }
        writer.writeInt(3, this.typingType.getValue());
    }

    @Override
    public String toString() {
        String res = "update Typing{";
        res += "peer=" + this.peer;
        res += ", uid=" + this.uid;
        res += ", typingType=" + this.typingType;
        res += "}";
        return res;
    }

    @Override
    public int getHeaderKey() {
        return HEADER;
    }
}