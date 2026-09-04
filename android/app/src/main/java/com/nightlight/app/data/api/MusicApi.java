package com.nightlight.app.data.api;

import com.nightlight.app.data.api.dto.ApiResponse;
import com.nightlight.app.data.api.dto.LyricsDtos;
import com.nightlight.app.data.api.dto.SongDtos;
import com.nightlight.app.data.api.dto.TrendingDtos;

import java.util.List;

import retrofit2.Call;
import retrofit2.http.GET;
import retrofit2.http.Query;

/** Retrofit contract for the JioSaavn music-proxy endpoints. */
public interface MusicApi {

    @GET("search/songs")
    Call<ApiResponse<SongDtos.SearchSongsDto>> searchSongs(
            @Query("query") String query,
            @Query("page") int page,
            @Query("limit") int limit);

    @GET("songs")
    Call<ApiResponse<List<SongDtos.SongDto>>> getSongs(@Query("ids") String ids);

    /** Related-song radio: other tracks seeded from the current one. */
    @GET("search/radio")
    Call<ApiResponse<SongDtos.SearchSongsDto>> getRadio(
            @Query("seedId") String seedId,
            @Query("seedName") String seedName,
            @Query("artists") String artists,
            @Query("album") String album,
            @Query("limit") int limit);

    /** Synchronized lyrics for a song. */
    @GET("lyrics")
    Call<ApiResponse<LyricsDtos.LyricsDto>> getLyrics(
            @Query("title") String title,
            @Query("artist") String artist,
            @Query("album") String album,
            @Query("durationMs") long durationMs);

    /** Real trending data: top chart songs, trending albums and chart list. */
    @GET("search/trending")
    Call<ApiResponse<TrendingDtos.TrendingDto>> getTrending();
}