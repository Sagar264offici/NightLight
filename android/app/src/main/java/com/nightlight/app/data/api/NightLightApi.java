package com.nightlight.app.data.api;

import com.nightlight.app.data.api.dto.ApiResponse;
import com.nightlight.app.data.api.dto.ImportDtos;
import com.nightlight.app.data.api.dto.Requests;
import com.nightlight.app.data.api.dto.SessionsDtos;
import com.nightlight.app.data.api.dto.UserDtos;
import com.nightlight.app.data.api.dto.WeatherDtos;

import retrofit2.Call;
import retrofit2.http.Body;
import retrofit2.http.DELETE;
import retrofit2.http.GET;
import retrofit2.http.PATCH;
import retrofit2.http.POST;
import retrofit2.http.PUT;
import retrofit2.http.Path;
import retrofit2.http.Query;

/** Retrofit contract for the MongoDB-backed NightLight user-data endpoints. */
public interface NightLightApi {

    // Auth
    @POST("auth/register")
    Call<ApiResponse<UserDtos.AuthDataDto>> register(@Body Requests.RegisterRequest body);

    // User-data resources are namespaced under /api/me to keep them distinct
    // from the music-proxy module (which owns /api/playlists, /api/songs, ...).
    // Likes
    @GET("me/likes/ids")
    Call<ApiResponse<UserDtos.LikedIdsDto>> getLikedIds();

    @GET("me/likes")
    Call<ApiResponse<UserDtos.LikesDto>> getLikes(@Query("page") int page, @Query("limit") int limit);

    @PUT("me/likes/{trackId}")
    Call<ApiResponse<Object>> likeTrack(@Path("trackId") String trackId, @Body Requests.LikeRequest body);

    @DELETE("me/likes/{trackId}")
    Call<ApiResponse<Object>> unlikeTrack(@Path("trackId") String trackId);

    // Recently played
    @GET("me/recently-played")
    Call<ApiResponse<UserDtos.RecentListDto>> getRecentlyPlayed(@Query("limit") int limit);

    @POST("me/recently-played")
    Call<ApiResponse<Object>> recordPlay(@Body Requests.LikeRequest body);

    // Search history
    @GET("me/search-history")
    Call<ApiResponse<UserDtos.HistoryListDto>> getSearchHistory(@Query("limit") int limit);

    @POST("me/search-history")
    Call<ApiResponse<UserDtos.HistoryDto>> addSearchHistory(@Body Requests.SearchHistoryRequest body);

    @DELETE("me/search-history/{id}")
    Call<ApiResponse<Object>> deleteSearchHistory(@Path("id") String id);

    @DELETE("me/search-history")
    Call<ApiResponse<Object>> clearSearchHistory();

    // Playlists
    @GET("me/playlists")
    Call<ApiResponse<UserDtos.PlaylistListDto>> getPlaylists();

    @POST("me/playlists")
    Call<ApiResponse<UserDtos.PlaylistDetailDto>> createPlaylist(@Body Requests.PlaylistCreateRequest body);

    @GET("me/playlists/{id}")
    Call<ApiResponse<UserDtos.PlaylistDetailDto>> getPlaylist(@Path("id") String id);

    @PATCH("me/playlists/{id}")
    Call<ApiResponse<UserDtos.PlaylistDetailDto>> updatePlaylist(@Path("id") String id, @Body Requests.PlaylistUpdateRequest body);

    @DELETE("me/playlists/{id}")
    Call<ApiResponse<Object>> deletePlaylist(@Path("id") String id);

    @POST("me/playlists/{id}/tracks")
    Call<ApiResponse<UserDtos.PlaylistTracksDto>> addPlaylistTrack(@Path("id") String id, @Body Requests.AddTrackRequest body);

    @DELETE("me/playlists/{id}/tracks/{trackId}")
    Call<ApiResponse<UserDtos.PlaylistTracksDto>> removePlaylistTrack(@Path("id") String id, @Path("trackId") String trackId);

    // Playlist conversion (Spotify / YouTube -> library)
    @POST("import/playlist")
    Call<ApiResponse<ImportDtos.ImportResultDto>> importPlaylist(@Body Requests.ImportRequest body);

    // Context
    @GET("context/weather")
    Call<ApiResponse<WeatherDtos.WeatherDto>> getWeather();

    // Listen together sessions
    @POST("sessions/create")
    Call<ApiResponse<SessionsDtos.SessionDto>> createSession(@Body SessionsDtos.CreateRequest body);

    @POST("sessions/join")
    Call<ApiResponse<SessionsDtos.SessionDto>> joinSession(@Body SessionsDtos.JoinRequest body);

    @GET("sessions/{code}")
    Call<ApiResponse<SessionsDtos.SessionDto>> getSession(@Path("code") String code);

    @PUT("sessions/{code}/state")
    Call<ApiResponse<SessionsDtos.SessionDto>> updateSessionState(
            @Path("code") String code, @Body SessionsDtos.UpdateStateRequest body);
}