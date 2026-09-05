if(NOT TARGET games-frame-pacing::swappy)
add_library(games-frame-pacing::swappy SHARED IMPORTED)
set_target_properties(games-frame-pacing::swappy PROPERTIES
    IMPORTED_LOCATION "/Users/ashupathak/.gradle/caches/8.9/transforms/dea39371837263f9029a6efe02992a29/transformed/games-frame-pacing-2.1.2/prefab/modules/swappy/libs/android.armeabi-v7a/libswappy.so"
    INTERFACE_INCLUDE_DIRECTORIES "/Users/ashupathak/.gradle/caches/8.9/transforms/dea39371837263f9029a6efe02992a29/transformed/games-frame-pacing-2.1.2/prefab/modules/swappy/include"
    INTERFACE_LINK_LIBRARIES ""
)
endif()

if(NOT TARGET games-frame-pacing::swappy_static)
add_library(games-frame-pacing::swappy_static STATIC IMPORTED)
set_target_properties(games-frame-pacing::swappy_static PROPERTIES
    IMPORTED_LOCATION "/Users/ashupathak/.gradle/caches/8.9/transforms/dea39371837263f9029a6efe02992a29/transformed/games-frame-pacing-2.1.2/prefab/modules/swappy_static/libs/android.armeabi-v7a/libswappy_static.a"
    INTERFACE_INCLUDE_DIRECTORIES "/Users/ashupathak/.gradle/caches/8.9/transforms/dea39371837263f9029a6efe02992a29/transformed/games-frame-pacing-2.1.2/prefab/modules/swappy_static/include"
    INTERFACE_LINK_LIBRARIES ""
)
endif()

