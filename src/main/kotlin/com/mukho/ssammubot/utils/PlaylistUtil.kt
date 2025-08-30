package com.mukho.ssammubot.utils

class PlaylistUtil {
    companion object {
        private val playlists = listOf(
            "[여돌/3:13:52] https://www.youtube.com/watch?v=6I-IpCwCrag",
            "[노동요/1:04:10] https://www.youtube.com/watch?v=-qLh7db_9KU",
            "[K-POP/2:24:59] https://www.youtube.com/watch?v=t1XTy-KVpE0",
            "[힙합/2:08:31] https://www.youtube.com/watch?v=xeCsFtwVozo",
            "[이마트/5:00:10] https://www.youtube.com/watch?v=QUXKib-jfEM",
            "[00년대/6:34:02] https://www.youtube.com/watch?v=gw3ltsoYBtI",
            "[10년/4:40:20] https://www.youtube.com/watch?v=JPg4E4w_ZyE",
            "[힙합/2:00:00] https://www.youtube.com/watch?v=GZKj-PRPc2c",
            "[노동요/2:34:24] https://www.youtube.com/watch?v=RDypwcB7ONY",

            "[믹스/1:42:51] https://www.youtube.com/watch?v=Fc6STsaeFLU",
            "[빅뱅/1:48:53] https://www.youtube.com/watch?v=9sovklDiNk0",
            "[POP/55:34] https://www.youtube.com/watch?v=kPDn_fEjs2s",
            "[인디/1:24:37] https://www.youtube.com/watch?v=AnqkPWH3A6I",

            "[여름/2:31:32] https://www.youtube.com/watch?v=DqVishhW3aA",
            "[신남/2:55:21] https://www.youtube.com/watch?v=L3fvGsxqpFc",
            "[여름/3:41:34] https://www.youtube.com/watch?v=ofnp63sk8wM",
            "[9000여름/1:06:56] https://www.youtube.com/watch?v=ioxTNNw8K1s",
            "[인디/2:04:29] https://www.youtube.com/watch?v=f4jS6yW83MU"
        )

        fun randomPlaylist(): String {
            return playlists.random()
        }
    }
}