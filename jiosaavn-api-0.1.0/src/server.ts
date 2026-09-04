import { AlbumController, ArtistController, SearchController, SongController } from '#modules/index'
import { PlaylistController } from '#modules/playlists/controllers'
import { AuthController } from '#modules/auth/controllers'
import { UserDataController } from '#modules/userdata/controllers'
import { ImportController } from '#modules/importer/controllers'
import { LyricsController } from '#modules/lyrics/controllers'
import { SessionsController } from '#modules/sessions/controllers'
import { WeatherController } from '#modules/context/controllers'
import { App } from './app'

const app = new App([
  new SearchController(),
  new SongController(),
  new AlbumController(),
  new ArtistController(),
  new PlaylistController(),
  new AuthController(),
  new UserDataController(),
  new ImportController(),
  new LyricsController(),
  new SessionsController(),
  new WeatherController()
]).getApp()

export default app