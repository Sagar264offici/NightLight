if(NOT TARGET game-activity::game-activity)
add_library(game-activity::game-activity STATIC IMPORTED)
set_target_properties(game-activity::game-activity PROPERTIES
    IMPORTED_LOCATION "/Users/ashupathak/.gradle/caches/8.9/transforms/fc45675d727d020b44a34b8b09a31478/transformed/games-activity-4.4.0/prefab/modules/game-activity/libs/android.armeabi-v7a/libgame-activity.a"
    INTERFACE_INCLUDE_DIRECTORIES "/Users/ashupathak/.gradle/caches/8.9/transforms/fc45675d727d020b44a34b8b09a31478/transformed/games-activity-4.4.0/prefab/modules/game-activity/include"
    INTERFACE_LINK_LIBRARIES ""
)
endif()

if(NOT TARGET game-activity::game-activity_static)
add_library(game-activity::game-activity_static STATIC IMPORTED)
set_target_properties(game-activity::game-activity_static PROPERTIES
    IMPORTED_LOCATION "/Users/ashupathak/.gradle/caches/8.9/transforms/fc45675d727d020b44a34b8b09a31478/transformed/games-activity-4.4.0/prefab/modules/game-activity_static/libs/android.armeabi-v7a/libgame-activity_static.a"
    INTERFACE_INCLUDE_DIRECTORIES "/Users/ashupathak/.gradle/caches/8.9/transforms/fc45675d727d020b44a34b8b09a31478/transformed/games-activity-4.4.0/prefab/modules/game-activity_static/include"
    INTERFACE_LINK_LIBRARIES ""
)
endif()

