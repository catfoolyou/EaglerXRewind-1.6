/*
 * Copyright (c) 2025 lax1dude, ayunami2000. All Rights Reserved.
 * 
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND
 * ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED
 * WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE DISCLAIMED.
 * IN NO EVENT SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE LIABLE FOR ANY DIRECT,
 * INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT
 * NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR
 * PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY,
 * WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE)
 * ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE
 * POSSIBILITY OF SUCH DAMAGE.
 * 
 */

package net.lax1dude.eaglercraft.backend.rewind_v1_6.base;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.*;
import java.util.zip.Deflater;
import java.util.zip.Inflater;

import io.netty.buffer.ByteBuf;
import io.netty.channel.Channel;
import net.lax1dude.eaglercraft.backend.rewind_v1_6.base.zstream.HackedBufferedInputStream;
import net.lax1dude.eaglercraft.backend.rewind_v1_6.base.zstream.HackedBufferedOutputStream;
import net.lax1dude.eaglercraft.backend.rewind_v1_6.base.zstream.HackedDataOutputStream;
import net.lax1dude.eaglercraft.backend.rewind_v1_6.base.zstream.ReusableGZIPInputStream;
import net.lax1dude.eaglercraft.backend.rewind_v1_6.base.zstream.ReusableGZIPOutputStream;
import net.lax1dude.eaglercraft.backend.server.api.IComponentHelper;
import net.lax1dude.eaglercraft.backend.server.api.IEaglerPlayer;
import net.lax1dude.eaglercraft.backend.server.api.IEaglerXServerAPI;
import net.lax1dude.eaglercraft.backend.server.api.collect.HPPC;
import net.lax1dude.eaglercraft.backend.server.api.collect.IntSet;
import net.lax1dude.eaglercraft.backend.server.api.collect.ObjectObjectMap;
import net.lax1dude.eaglercraft.backend.server.api.nbt.INBTContext;
import net.lax1dude.eaglercraft.backend.server.api.rewind.IMessageController;
import net.lax1dude.eaglercraft.backend.server.api.rewind.IOutboundInjector;
import net.lax1dude.eaglercraft.v1_8.socket.protocol.pkt.server.SPacketVoiceSignalGlobalEAG;

public class RewindPlayer<PlayerObject> {

	private final RewindProtocol<PlayerObject> rewind;
	private final IMessageController messageController;
	private final IOutboundInjector outboundInjector;
	private final Channel channel;
	private final IRewindLogger logger;
	private IEaglerPlayer<PlayerObject> eaglerPlayer;

	private INBTContext nbtContext;
	private IComponentHelper componentHelper;
	private TabListTracker tabList;
	private ObjectObjectMap<UUID, String> voiceGlobalMap;
	private ObjectObjectMap<String, UUID> voiceGlobalMapInv;
	private Deflater notDeflater;
	private Deflater notGZipper;
	private Inflater ungzipper;
	private ReusableGZIPInputStream ungzipperStream;
	private DataInputStream ungzipperStreamOuter;
	private ReusableGZIPOutputStream gzipperStream;
	private DataOutputStream gzipperStreamOuter;

	private final IntSet enchWindows;

	private double x = 0;
	private double y = 0;
	private double z = 0;
	private float yaw = 0;
	private float pitch = 0;
	private boolean isSneaking = false;

	private byte[] temp1;

	public RewindPlayer(RewindProtocol<PlayerObject> rewind, IMessageController messageController,
			IOutboundInjector outboundInjector, Channel channel, String logName) {
		this.rewind = rewind;
		this.messageController = messageController;
		this.outboundInjector = outboundInjector;
		this.channel = channel;
		this.logger = rewind.logger().createSubLogger(logName);
		this.enchWindows = rewind.getServerAPI().getHPPC().createIntHashSet();
	}

	public RewindProtocol<PlayerObject> getRewind() {
		return rewind;
	}

	public IRewindLogger logger() {
		return logger;
	}

	public IEaglerPlayer<PlayerObject> getPlayer() {
		return eaglerPlayer;
	}

	public IMessageController getMessageController() {
		return messageController;
	}

	public IOutboundInjector getOutboundInjector() {
		return outboundInjector;
	}

	public Channel getChannel() {
		return channel;
	}

	public INBTContext getNBTContext() {
		if (this.nbtContext == null) {
			this.nbtContext = rewind.getServerAPI().getNBTHelper().createThreadContext(512);
		}
		return this.nbtContext;
	}

	public IComponentHelper getComponentHelper() {
		if (this.componentHelper == null) {
			this.componentHelper = rewind.getServerAPI().getComponentHelper();
		}
		return this.componentHelper;
	}

	public TabListTracker getTabList() {
		if (this.tabList == null) {
			this.tabList = new TabListTracker(rewind.getServerAPI().getHPPC());
		}
		return this.tabList;
	}

	public Deflater getNotDeflater() {
		if (this.notDeflater == null) {
			// Note: Always use compression level 0, websocket is already compressed!
			this.notDeflater = new Deflater(0);
		}
		return this.notDeflater;
	}

	public Deflater getNotGZipper() {
		if (this.notGZipper == null) {
			this.notGZipper = new Deflater(0, true);
		}
		return this.notGZipper;
	}

	public Inflater getUnGZipper() {
		if (this.ungzipper == null) {
			this.ungzipper = new Inflater(true);
		}
		return this.ungzipper;
	}

	public DataInputStream createGZIPInputStream(ByteBuf buf, int limit) throws IOException {
		if (this.ungzipperStream == null) {
			this.ungzipperStream = new ReusableGZIPInputStream(getUnGZipper(), getTempBuffer1());
			this.ungzipperStreamOuter = new DataInputStream(new HackedBufferedInputStream(ungzipperStream, 2048));
		}
		this.ungzipperStream.setInput(buf, limit);
		return this.ungzipperStreamOuter;
	}

	public DataOutputStream createGZIPOutputStream(ByteBuf buf) {
		if (this.gzipperStream == null) {
			this.gzipperStream = new ReusableGZIPOutputStream(getNotGZipper(), getTempBuffer1());
			this.gzipperStreamOuter = new HackedDataOutputStream(new HackedBufferedOutputStream(gzipperStream, 2048));
		}
		this.gzipperStream.setOutput(buf);
		return this.gzipperStreamOuter;
	}

	public IntSet getEnchWindows() {
		return this.enchWindows;
	}

	public double getX() {
		return this.x;
	}

	public double getY() {
		return this.y;
	}

	public double getZ() {
		return this.z;
	}

	public float getYaw() {
		return this.yaw;
	}

	public float getPitch() {
		return this.pitch;
	}

	public void setPos(double x, double y, double z) {
		this.x = x;
		this.y = y;
		this.z = z;
	}

	public void setLook(float yaw, float pitch) {
		this.yaw = yaw;
		this.pitch = pitch;
	}

	public boolean isSneaking() {
		return isSneaking;
	}

	public void setSneaking(boolean sneaking) {
		this.isSneaking = sneaking;
	}

	public byte[] getTempBuffer1() {
		if (this.temp1 == null) {
			this.temp1 = new byte[1];
		}
		return this.temp1;
	}

	public void releaseVoiceGlobalMap() {
		voiceGlobalMap = null;
		voiceGlobalMapInv = null;
	}

	public void handleVoiceGlobal(Collection<SPacketVoiceSignalGlobalEAG.UserData> userDatas) {
		IEaglerXServerAPI<?> api = rewind.getServerAPI();
		HPPC hppc = api.getHPPC();
		voiceGlobalMap = hppc.createObjectObjectHashMap(userDatas.size());
		voiceGlobalMapInv = hppc.createObjectObjectHashMap(userDatas.size());
		for (SPacketVoiceSignalGlobalEAG.UserData userData : userDatas) {
			UUID uuid = api.intern(new UUID(userData.uuidMost, userData.uuidLeast));
			String name = userData.username.intern();
			voiceGlobalMap.put(uuid, name);
			voiceGlobalMapInv.put(name, uuid);
		}
	}

	public String getVoicePlayerByUUID(UUID uuid) {
		return voiceGlobalMap != null ? voiceGlobalMap.get(uuid) : null;
	}

	public UUID getVoicePlayerByName(String name) {
		return voiceGlobalMapInv != null ? voiceGlobalMapInv.get(name) : null;
	}

	public void handlePlayerCreate(IEaglerPlayer<PlayerObject> eaglerPlayer) {
		this.eaglerPlayer = eaglerPlayer;
	}

	public void handlePlayerDestroy() {

	}

	public void releaseNatives() {
		if (notDeflater != null) {
			notDeflater.end();
		}
		if (notGZipper != null) {
			notGZipper.end();
		}
		if (ungzipper != null) {
			ungzipper.end();
		}
	}

}
