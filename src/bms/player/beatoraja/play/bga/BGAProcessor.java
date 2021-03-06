package bms.player.beatoraja.play.bga;

import java.nio.file.*;
import java.util.logging.Logger;

import bms.model.BMSModel;
import bms.model.TimeLine;
import bms.player.beatoraja.Config;
import bms.player.beatoraja.PlayerConfig;
import bms.player.beatoraja.ResourcePool;
import bms.player.beatoraja.play.BMSPlayer;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.graphics.*;
import com.badlogic.gdx.graphics.Texture.TextureFilter;
import com.badlogic.gdx.graphics.g2d.SpriteBatch;
import com.badlogic.gdx.graphics.glutils.ShaderProgram;
import com.badlogic.gdx.math.Rectangle;
import com.badlogic.gdx.utils.Array;
import com.badlogic.gdx.utils.IntMap;

/**
 * BGAのリソース管理、描画用クラス
 *
 * @author exch
 */
public class BGAProcessor {

	private BMSModel model;
	private Config config;
	private PlayerConfig player;
	private float progress = 0;

	private IntMap<MovieProcessor> mpgmap = new IntMap<MovieProcessor>();
	
	private ResourcePool<String, MovieProcessor> mpgresource = new ResourcePool<String, MovieProcessor>(1) {

		@Override
		protected MovieProcessor load(String key) {
			if (config.getMovieplayer() == Config.MOVIEPLAYER_FFMPEG) {
				MovieProcessor mm = new FFmpegProcessor(config.getFrameskip());
				mm.create(key);
				return mm;
			}
			if (config.getMovieplayer() == Config.MOVIEPLAYER_VLC && config.getVlcpath().length() > 0) {
				MovieProcessor mm = new VLCMovieProcessor(config.getVlcpath());
				mm.create(key);
				return mm;
			}
			return null;
		}

		@Override
		protected void dispose(MovieProcessor resource) {
			resource.dispose();
		}

	};

	public static final String[] mov_extension = { "mpg", "mpeg", "m1v", "m2v", "avi", "wmv", "mp4" };

	/**
	 * BGAイメージのキャッシュ枚数
	 */
	private static final int BGACACHE_SIZE = 256;

	/**
	 * 再生中のBGAID
	 */
	private int playingbgaid = -1;
	/**
	 * 再生中のレイヤーID
	 */
	private int playinglayerid = -1;
	/**
	 * ミスレイヤー表示開始時間
	 */
	private int misslayertime;

	private int getMisslayerduration;
	/**
	 * 現在のミスレイヤーシーケンス
	 */
	private int[] misslayer = null;

	private int prevrendertime;
	/**
	 * レイヤー描画用シェーダ
	 */
	private ShaderProgram layershader;

	private BGImageProcessor cache;

	private Texture blanktex;

	private TimeLine[] timelines;
	private int pos;

	public BGAProcessor(Config config, PlayerConfig player) {
		this.config = config;
		this.player = player;

		String vertex = "attribute vec4 " + ShaderProgram.POSITION_ATTRIBUTE + ";\n" //
				+ "attribute vec4 " + ShaderProgram.COLOR_ATTRIBUTE + ";\n" //
				+ "attribute vec2 " + ShaderProgram.TEXCOORD_ATTRIBUTE + "0;\n" //
				+ "uniform mat4 u_projTrans;\n" //
				+ "varying vec4 v_color;\n" //
				+ "varying vec2 v_texCoords;\n" //
				+ "\n" //
				+ "void main()\n" //
				+ "{\n" //
				+ "   v_color = " + ShaderProgram.COLOR_ATTRIBUTE + ";\n" //
				+ "   v_texCoords = " + ShaderProgram.TEXCOORD_ATTRIBUTE + "0;\n" //
				+ "   gl_Position =  u_projTrans * " + ShaderProgram.POSITION_ATTRIBUTE + ";\n" //
				+ "}\n";

		String fragment = "#ifdef GL_ES\n" //
				+ "#define LOWP lowp\n" //
				+ "precision mediump float;\n" //
				+ "#else\n" //
				+ "#define LOWP \n" //
				+ "#endif\n" //
				+ "varying LOWP vec4 v_color;\n" //
				+ "varying vec2 v_texCoords;\n" //
				+ "uniform sampler2D u_texture;\n" //
				+ "void main()\n"//
				+ "{\n" //
				+ "    vec4 c4 = texture2D(u_texture, v_texCoords);\n"
				+ "    if(c4.r == 0.0 && c4.g == 0.0 && c4.b == 0.0) "
				+ "{ gl_FragColor = v_color * vec4(c4.r, c4.g, c4.b, 0.0);}" + " else {gl_FragColor = v_color * c4;}\n"
				+ "}";
		layershader = new ShaderProgram(vertex, fragment);

		System.out.println(layershader.getLog());

		Pixmap blank = new Pixmap(1, 1, Pixmap.Format.RGBA8888);
		blank.setColor(Color.BLACK);
		blank.fill();
		blanktex = new Texture(blank);
		
		cache = new BGImageProcessor(BGACACHE_SIZE);
	}

	public synchronized void setModel(BMSModel model) {
		this.model = model;
		Array<TimeLine> tls = new Array<TimeLine>();
		for(TimeLine tl : model.getAllTimeLines()) {
			if(tl.getBGA() != -1 || tl.getLayer() != -1 || (tl.getPoor() != null && tl.getPoor().length > 0)) {
				tls.add(tl);
			}
		}
		timelines = tls.toArray(TimeLine.class);

		// BMS格納ディレクトリ
		Path dpath = Paths.get(model.getPath()).getParent();

		progress = 0;

		mpgmap.clear();
		int id = 0;
		cache.clear();

		for (String name : model.getBgaList()) {
			if (progress == 1) {
				break;
			}
			Path f = null;
			if (Files.exists(dpath.resolve(name))) {
				f = dpath.resolve(name);
			}
			if (f == null) {
				final int index = name.lastIndexOf('.');
				if (index != -1) {
					name = name.substring(0, index);
				}
				for (String mov : mov_extension) {
					final Path mpgfile = dpath.resolve(name + "." + mov);
					if (Files.exists(mpgfile)) {
						f = mpgfile;
						break;
					}
				}
				for (String mov : BGImageProcessor.pic_extension) {
					final Path picfile = dpath.resolve(name + "." + mov);
					if (Files.exists(picfile)) {
						f = picfile;
						break;
					}
				}
			}

			if (f != null) {
				boolean isMovie = false;
				for (String mov : mov_extension) {
					if (f.getFileName().toString().toLowerCase().endsWith(mov)) {
						try {
							MovieProcessor mm = mpgresource.get(f.toString());
							mpgmap.put(id, mm);
							isMovie = true;
							break;
						} catch (Throwable e) {
							Logger.getGlobal().warning("BGAファイル読み込み失敗。" + e.getMessage());
							e.printStackTrace();
						}					
					}
				}
				if(isMovie) {
				} else {
					cache.put(id, f);					
				}
			}

			progress += 1f / model.getBgaList().length;
			id++;
		}
		
		cache.disposeOld();
		Gdx.app.postRunnable(new Runnable() {
			@Override
			public void run() {
				mpgresource.disposeOld();
			}			
		});

		Logger.getGlobal().info("BGAファイル読み込み完了。BGA数:" + model.getBgaList().length);
		progress = 1;
	}

	public void abort() {
		progress = 1;
	}

	/**
	 * BGAの初期データをあらかじめキャッシュする
	 */
	public void prepare(BMSPlayer player) {
		if (model == null) {
			return;
		}
		pos = 0;
		if(cache != null) {
			cache.prepare(timelines);			
		}
		for (MovieProcessor mp : mpgmap.values()) {
			mp.stop();				
			if (mp instanceof FFmpegProcessor) {
				((FFmpegProcessor) mp).setBMSPlayer(player);
			}
		}
		playingbgaid = -1;
		playinglayerid = -1;
		misslayertime = 0;
		misslayer = null;
		prevrendertime = 0;		
	}

	private Texture getBGAData(int id, boolean cont) {
		if (progress != 1 || id == -1) {
			return null;
		}

		MovieProcessor mp = getMovieProcessor(id);
		if(mp != null) {
			if (!cont) {
				mp.play(false);
			}
			return mp.getFrame();			
		}
		return cache != null ? cache.getTexture(id) : null;
	}

	public void drawBGA(SpriteBatch sprite, Rectangle r, int time) {
		if (time < 0 || timelines == null) {
			prevrendertime = -1;
			sprite.draw(blanktex, r.x, r.y, r.width, r.height);
			return;
		}
		boolean rbga = true;
		boolean rlayer = true;
		for (int i = pos; i < timelines.length; i++) {
			final TimeLine tl = timelines[i];
			if (tl.getTime() > time) {
				break;
			}

			if (tl.getTime() > prevrendertime) {
				if (tl.getBGA() == -2) {
					playingbgaid = -1;
					rbga = false;
				} else if (tl.getBGA() >= 0) {
					playingbgaid = tl.getBGA();
					rbga = false;
				}
				if (tl.getLayer() == -2) {
					playinglayerid = -1;
					rlayer = false;
				} else if (tl.getLayer() >= 0) {
					playinglayerid = tl.getLayer();
					rlayer = false;
				}

				if (tl.getPoor() != null && tl.getPoor().length > 0) {
					misslayer = tl.getPoor();
				}
			} else {
				pos++;
			}
		}

		if (misslayer != null && misslayertime != 0 && time >= misslayertime && time < misslayertime + getMisslayerduration) {
			// draw miss layer
			Texture miss = getBGAData(misslayer[misslayer.length * (time - misslayertime) / getMisslayerduration], true);
			if (miss != null) {
				miss.setFilter(TextureFilter.Linear, TextureFilter.Linear);
				drawBGAFixRatio(sprite, r, miss);
			}
		} else {
			// draw BGA
			final Texture playingbgatex = getBGAData(playingbgaid, rbga);
			if (playingbgatex != null) {
				final MovieProcessor mp = getMovieProcessor(playingbgaid);
				playingbgatex.setFilter(TextureFilter.Linear, TextureFilter.Linear);
				if (mp != null) {
					final ShaderProgram shader = mp.getShader();
					sprite.setShader(shader);
					drawBGAFixRatio(sprite, r, playingbgatex);
					sprite.setShader(null);
				} else {
					drawBGAFixRatio(sprite, r, playingbgatex);
				}
			} else {
				sprite.draw(blanktex, r.x, r.y, r.width, r.height);
			}
			// draw layer
			final Texture playinglayertex = getBGAData(playinglayerid, rlayer);
			if (playinglayertex != null) {
				final MovieProcessor mp = getMovieProcessor(playinglayerid);
				if (mp != null) {
					playinglayertex.setFilter(TextureFilter.Linear, TextureFilter.Linear);
					final ShaderProgram shader = mp.getShader();
					sprite.setShader(shader);
					drawBGAFixRatio(sprite, r, playinglayertex);
					sprite.setShader(null);
				} else if (layershader.isCompiled()) {
					sprite.setShader(layershader);
					drawBGAFixRatio(sprite, r, playinglayertex);
					sprite.setShader(null);
				} else {
					drawBGAFixRatio(sprite, r, playinglayertex);
				}
			}
		}

		prevrendertime = time;
	}
	
	private MovieProcessor getMovieProcessor(int id) {
		return mpgmap.get(id);
	}

	/**
	 * Modify the aspect ratio and draw BGA
	 */
	private void drawBGAFixRatio(SpriteBatch sprite, Rectangle r, Texture bga){
		switch(config.getBgaExpand()) {
		case Config.BGAEXPAND_FULL:
	        sprite.draw(bga, r.x, r.y, r.width, r.height);
			break;
		case Config.BGAEXPAND_KEEP_ASPECT_RATIO:
			float fixx,fixy,fixheight,fixwidth;
			float movieaspect = (float)bga.getWidth() / bga.getHeight();
			float windowaspect = (float)r.width / r.height;
			float scaleheight = (float)windowaspect / movieaspect;
			float scalewidth  = (float)1.0f / scaleheight;
	        if(1.0f > scaleheight){
	        	fixx = r.x;
	            fixy = r.y+ (r.height * (1.0f - scaleheight)) / 2.0f;
	            fixheight = r.height * scaleheight;
	            fixwidth = r.width;
	        } else {
	            fixx = r.x+(r.width * (1.0f - scalewidth)) / 2.0f;
	            fixy = r.y;
	            fixheight = r.height;
	            fixwidth = r.width * scalewidth;
	        }
	        sprite.draw(bga, fixx, fixy, fixwidth, fixheight);
			break;
		case Config.BGAEXPAND_OFF:
            float w = Math.min(r.width, bga.getWidth());
            float h = Math.min(r.height, bga.getHeight());
	       	float x = r.x + (r.width - w) / 2;
            float y = r.y + (r.height - h) / 2;;
	        sprite.draw(bga, x, y, w, h);
			break;
		}
	}

	/**
	 * ミスレイヤー開始時間を設定する
	 *
	 * @param time
	 *            ミスレイヤー開始時間(ms)
	 */
	public void setMisslayerTme(int time) {
		misslayertime = time;
		getMisslayerduration = player.getMisslayerDuration();
	}

	public void stop() {
		for (MovieProcessor mpg : mpgmap.values()) {
			if (mpg != null) {
				mpg.stop();
			}
		}
	}

	/**
	 * リソースを開放する
	 */
	public void dispose() {
		if (cache != null) {
			cache.dispose();
		}
		mpgresource.dispose();
		try {
			layershader.dispose();
		} catch(Throwable e) {

		}
	}

	public float getProgress() {
		return progress;
	}
}
