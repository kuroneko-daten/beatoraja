package bms.player.beatoraja.select;

import bms.player.beatoraja.IRScoreData;
import bms.player.beatoraja.song.SongData;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * スコアデータのキャッシュ
 *
 * @author exch
 */
public abstract class ScoreDataCache {

    // TODO ResourcePoolベースに移行する

    /**
     * スコアデータのキャッシュ
     */
    private Map<String, IRScoreData>[] scorecache;

    public ScoreDataCache() {
        scorecache = new Map[3];
        for (int i = 0; i < scorecache.length; i++) {
            scorecache[i] = new HashMap();
        }
    }

    public IRScoreData readScoreData(SongData song, int lnmode) {
        if (scorecache[lnmode].containsKey(song.getSha256())) {
            return scorecache[lnmode].get(song.getSha256());
        }
        IRScoreData score = readScoreDatasFromSource(new SongData[]{song}, lnmode).get(song.getSha256());
        for (int i = 0; i < scorecache.length; i++) {
            if (!song.hasUndefinedLongNote() || i == lnmode) {
                scorecache[i].put(song.getSha256(), score);
            }
        }
        return score;
    }

    public Map<String, IRScoreData> readScoreDatas(SongData[] songs, int lnmode) {
        Map<String, IRScoreData> result = new HashMap<String, IRScoreData>(songs.length);
        List<SongData> noscore = new ArrayList<SongData>();
        for (SongData song : songs) {
            if (scorecache[lnmode].containsKey(song.getSha256())) {
                result.put(song.getSha256(), scorecache[lnmode].get(song.getSha256()));
            } else {
                noscore.add(song);
            }
        }

        Map<String, IRScoreData> scores = readScoreDatasFromSource(noscore.toArray(new SongData[noscore.size()]), lnmode);
        for (SongData song : noscore) {
            IRScoreData score = scores.get(song.getSha256());
            for (int i = 0; i < scorecache.length; i++) {
                if (!song.hasUndefinedLongNote() || i == lnmode) {
                    scorecache[i].put(song.getSha256(), score);
                }
            }
            result.put(song.getSha256(), score);
        }
        return result;
    }

    boolean existsScoreDataCache(SongData song, int lnmode) {
        return scorecache[lnmode].containsKey(song.getSha256());
    }

    public void clear() {
        for (Map<?, ?> cache : scorecache) {
            cache.clear();
        }
    }

    public void update(SongData song, int lnmode) {
        IRScoreData score = readScoreDatasFromSource(new SongData[]{song}, lnmode).get(song.getSha256());
        for (int i = 0; i < scorecache.length; i++) {
            if (!song.hasUndefinedLongNote() || i == lnmode) {
                scorecache[i].put(song.getSha256(), score);
            }
        }
    }
    
    protected abstract Map<String, IRScoreData> readScoreDatasFromSource(SongData[] songs, int lnmode);
}