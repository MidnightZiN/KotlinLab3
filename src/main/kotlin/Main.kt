import io.ktor.server.engine.*
import io.ktor.server.netty.*
import io.ktor.server.application.*
import io.ktor.http.*
import io.ktor.server.response.*
import io.ktor.server.request.*
import io.ktor.server.routing.*
import io.ktor.server.html.*
import kotlinx.html.*
import io.ktor.server.plugins.callloging.*
import kotlinx.html.stream.appendHTML

data class User(
    val name: String,
    val friends: MutableList<User> = mutableListOf(),
    val posts: MutableList<Post> = mutableListOf()
)

data class Post(
    val author: User,
    val title: String,
    val topic: String,
    val content: String,
    val date: String,
    val likes: MutableList<User> = mutableListOf(),
    val comments: MutableList<Comment> = mutableListOf()
)

data class Comment(
    val author: User,
    val content: String,
    val date: String
)

// ----------------- Utility Functions -----------------
fun <T> forEach(list: List<T>, action: (T) -> Unit) {
    for (element in list) {
        action(element)
    }
}

fun <T> filter(list: List<T>, predicate: (T) -> Boolean): List<T> {
    val result = mutableListOf<T>()
    for (element in list) {
        if (predicate(element)) {
            result.add(element)
        }
    }
    return result
}

fun <T, R> map(list: List<T>, transform: (T) -> R): List<R> {
    val result = mutableListOf<R>()
    for (element in list) {
        result.add(transform(element))
    }
    return result
}

fun <T> sortByDescending(list: List<T>, selector: (T) -> Int): List<T> {
    val mutable = list.toMutableList()
    var swapped: Boolean
    do {
        swapped = false
        for (i in 0 until mutable.size - 1) {
            if (selector(mutable[i]) < selector(mutable[i + 1])) {
                val temp = mutable[i]
                mutable[i] = mutable[i + 1]
                mutable[i + 1] = temp
                swapped = true
            }
        }
    } while (swapped)
    return mutable
}

val dateProvider = { "2024-12-06" }

fun createPost(author: User, title: String, topic: String, content: String, dateProvider: () -> String): Post {
    val post = Post(author, title, topic, content, dateProvider())
    author.posts.add(post)
    return post
}

fun createComment(author: User, post: Post, content: String, dateProvider: () -> String): Comment {
    val comment = Comment(author, content, dateProvider())
    post.comments.add(comment)
    return comment
}

fun likePost(post: Post, user: User) {
    var alreadyLiked = false
    for (u in post.likes) {
        if (u == user) {
            alreadyLiked = true
            break
        }
    }
    if (!alreadyLiked) {
        post.likes.add(user)
    }
}

fun addFriend(user: User, friend: User) {
    if (user != friend && !user.friends.contains(friend)) {
        user.friends.add(friend)
        friend.friends.add(user)
    }
}

fun findPopularPosts(users: List<User>): List<Post> {
    val allPosts = mutableListOf<Post>()
    for (u in users) {
        for (p in u.posts) {
            allPosts.add(p)
        }
    }
    return sortByDescending(allPosts) { p -> p.likes.size + p.comments.size }
}

fun analyzeUserActivity(users: List<User>): List<Pair<User, Int>> {
    val result = mutableListOf<Pair<User, Int>>()
    for (u in users) {
        val postsCount = u.posts.size
        var commentsCount = 0
        for (other in users) {
            for (p in other.posts) {
                for (c in p.comments) {
                    if (c.author == u) {
                        commentsCount++
                    }
                }
            }
        }
        val totalActivity = postsCount + commentsCount
        result.add(Pair(u, totalActivity))
    }
    val sorted = sortByDescending(result) { it.second }
    return sorted
}

fun recommendFriends(user: User, allUsers: List<User>): List<User> {
    val notFriends = filter(allUsers) { it != user && !user.friends.contains(it) }
    val candidatesWithScore = mutableListOf<Pair<User, Int>>()
    for (candidate in notFriends) {
        var commonFriendsCount = 0
        for (f in candidate.friends) {
            if (user.friends.contains(f)) {
                commonFriendsCount++
            }
        }
        candidatesWithScore.add(Pair(candidate, commonFriendsCount))
    }
    val sortedCandidates = sortByDescending(candidatesWithScore) { it.second }
    val filteredCandidates = filter(sortedCandidates) { it.second > 0 }
    return map(filteredCandidates) { it.first }
}

val allUsers = mutableListOf<User>()

fun findUserByName(users: List<User>, name: String): User? {
    for (u in users) {
        if (u.name == name) return u
    }
    return null
}
fun getTopicsStats(): List<Triple<String, Int, Int>> {
    // Собираем все посты
    val allPosts = mutableListOf<Post>()
    for (u in allUsers) {
        for (p in u.posts) {
            allPosts.add(p)
        }
    }

    // Группируем по темам
    val postsByTopic = mutableMapOf<String, MutableList<Post>>()
    for (p in allPosts) {
        postsByTopic.computeIfAbsent(p.topic) { mutableListOf() }.add(p)
    }

    // Подсчитываем статистику по темам: (тема, количество постов, количество лайков)
    val stats = mutableListOf<Triple<String, Int, Int>>()
    for ((topic, posts) in postsByTopic) {
        val postsCount = posts.size
        val likesCount = posts.sumOf { it.likes.size }
        stats.add(Triple(topic, postsCount, likesCount))
    }

    return stats
}

fun Routing.topicsChartRoute() {
    get("/topicsChart") {
        val stats = getTopicsStats()
        if (stats.isEmpty()) {
            call.respondHtml {
                body {
                    h2 { +"Статистика по темам" }
                    p { +"Нет постов для анализа." }
                    a("/") { +"На главную" }
                }
            }
            return@get
        }

        // Найдём максимальное число лайков, чтобы нормировать ширину полос
        val maxLikes = stats.maxOf { it.third }.coerceAtLeast(1)

        call.respondHtml {
            head {
                title { +"Диаграмма популярности тем" }
                style {
                    +"""
                    .bar-container {
                        width: 400px; 
                        background: #eee; 
                        border: 1px solid #ccc; 
                        margin: 5px 0; 
                        position: relative;
                    }
                    .bar {
                        background: #4CAF50; 
                        height: 20px; 
                    }
                    """.trimIndent()
                }
            }
            body {
                h2 { +"Диаграмма популярности тем" }
                p { +"Показывает отношение количества постов и лайков по темам." }

                // Таблица со статистикой
                table {
                    tr {
                        th { +"Тема" }
                        th { +"Кол-во постов" }
                        th { +"Лайков всего" }
                        th { +"Диаграмма (по лайкам)" }
                    }

                    for ((topic, postsCount, likesCount) in stats) {
                        tr {
                            td { +topic }
                            td { +"$postsCount" }
                            td { +"$likesCount" }

                            // Полоска. Длина полосы пропорциональна кол-ву лайков
                            val barWidth = (likesCount.toDouble() / maxLikes * 300).toInt()
                            td {
                                div("bar-container") {
                                    div("bar") {
                                        style = "width: ${barWidth}px;"
                                    }
                                }
                            }
                        }
                    }
                }

                br
                a("/") { +"На главную" }
            }
        }
    }
}


fun BODY.mainMenu() {
    h1 { +"Социальная сеть" }
    p { +"Добро пожаловать в нашу мини-соцсеть!" }
    ul {
        li { a("/feed") { +"Лента постов" } }
        li { a("/createUser") { +"Создать пользователя" } }
        li { a("/createPost") { +"Создать пост" } }
        li { a("/createComment") { +"Создать комментарий" } }
        li { a("/likePost") { +"Поставить лайк посту" } }
        li { a("/addFriend") { +"Добавить друга" } }
        li { a("/popularPosts") { +"Популярные посты" } }
        li { a("/activity") { +"Анализ активности пользователей" } }
        li { a("/recommend") { +"Рекомендация друзей" } }
    }
}

fun main() {
    embeddedServer(Netty, port = 8080) {
        install(CallLogging)

        routing {
            // Главная страница с меню
            get("/") {
                call.respondHtml(HttpStatusCode.OK) {
                    head { title { +"Главная" } }
                    body {
                        mainMenu()
                    }
                }
            }

            // Страница ленты постов (просто отображение)
            get("/feed") {
                val allPosts = mutableListOf<Post>()
                for (u in allUsers) for (p in u.posts) allPosts.add(p)
                val sortedPosts = findPopularPosts(allUsers)

                call.respondHtml {
                    body {
                        h1 { +"Лента постов" }
                        if (sortedPosts.isEmpty()) {
                            p { +"Нет постов." }
                        } else {
                            for ((index, post) in sortedPosts.withIndex()) {
                                div {
                                    h3 { +"Автор: ${post.author.name}" }
                                    p { +"Заголовок: ${post.title}" }
                                    p { +"Тема: ${post.topic}" }
                                    p { +"Дата: ${post.date}" }
                                    p { +"Лайков: ${post.likes.size}, Комментариев: ${post.comments.size}" }
                                    p { +"Содержимое: ${post.content}" }

                                    // Форма лайка
                                    form(action = "/likePostDirect", method = FormMethod.post) {
                                        hiddenInput(name = "postIndex") { value = index.toString() }
                                        textInput(name = "userName") {
                                            placeholder = "Ваше имя"
                                            size = "20"
                                        }
                                        submitInput { value = "Лайк" }
                                    }

                                    // Ссылка "Комментировать"
                                    a("/commentOnPost?postIndex=$index") { +"Комментировать" }
                                    br

                                    // Новая кнопка: Просмотреть комментарии
                                    a("/viewComments?postIndex=$index") { +"Просмотреть комментарии" }
                                }
                                hr {}
                            }
                        }
                        br
                        a("/") { +"На главную" }
                    }
                }
            }

            // Страница для комментирования конкретного поста из ленты
            get("/commentOnPost") {
                val postIndexStr = call.request.queryParameters["postIndex"]
                val allPosts = mutableListOf<Post>()
                for (u in allUsers) for (p in u.posts) allPosts.add(p)

                call.respondHtml {
                    body {
                        h2 { +"Комментирование поста" }
                        val error = when {
                            allPosts.isEmpty() -> "Нет постов."
                            postIndexStr == null -> "Не указан индекс поста."
                            postIndexStr.toIntOrNull() == null -> "Неверный индекс поста."
                            postIndexStr.toInt() !in allPosts.indices -> "Нет поста с таким индексом."
                            else -> null
                        }

                        if (error != null) {
                            p { +"Ошибка: $error" }
                            a("/feed") { +"Назад к ленте" }
                        } else {
                            val post = allPosts[postIndexStr!!.toInt()]
                            p { +"Пост автора ${post.author.name}: \"${post.content}\"" }
                            form(action = "/createCommentForPost", method = FormMethod.post) {
                                hiddenInput(name = "postIndex") { value = postIndexStr }
                                textInput(name = "commenter") { placeholder = "Ваше имя" }
                                br
                                textInput(name = "commentContent") { placeholder = "Текст комментария" }
                                br
                                submitInput { value = "Добавить комментарий" }
                            }
                            a("/feed") { +"Назад к ленте" }
                        }
                    }
                }
            }

            // Обработка комментария для конкретного поста
            post("/createCommentForPost") {
                val params = call.receiveParameters()
                val commenterName = params["commenter"]?.trim() ?: ""
                val commentContent = params["commentContent"]?.trim() ?: ""
                val postIndexStr = params["postIndex"]

                val commenter = findUserByName(allUsers, commenterName)
                val allPosts = mutableListOf<Post>()
                for (u in allUsers) for (p in u.posts) allPosts.add(p)

                val error = when {
                    commenterName.isBlank() -> "Не указано имя комментатора."
                    commenter == null -> "Комментатор не найден. Сначала создайте пользователя."
                    commentContent.isBlank() -> "Комментарий не может быть пустым."
                    allPosts.isEmpty() -> "Нет постов."
                    postIndexStr == null -> "Не указан индекс поста."
                    postIndexStr.toIntOrNull() == null -> "Неверный индекс поста."
                    postIndexStr.toInt() !in allPosts.indices -> "Нет поста с таким индексом."
                    else -> null
                }

                call.respondHtml {
                    body {
                        if (error != null) {
                            p { +"Ошибка: $error" }
                            a("/feed") { +"Назад к ленте" }
                        } else {
                            val post = allPosts[postIndexStr!!.toInt()]
                            createComment(commenter!!, post, commentContent, dateProvider)
                            p { +"Комментарий добавлен." }
                            a("/feed") { +"Назад к ленте" }
                        }
                    }
                }
            }

            // Лайк поста напрямую из ленты
            post("/likePostDirect") {
                val params = call.receiveParameters()
                val userName = params["userName"]?.trim() ?: ""
                val postIndexStr = params["postIndex"]?.trim()

                val user = findUserByName(allUsers, userName)
                val allPosts = mutableListOf<Post>()
                for (u in allUsers) for (p in u.posts) allPosts.add(p)

                val error = when {
                    userName.isBlank() -> "Не указано имя пользователя."
                    user == null -> "Пользователь не найден. Сначала создайте пользователя."
                    allPosts.isEmpty() -> "Нет постов."
                    postIndexStr == null -> "Не указан индекс поста."
                    postIndexStr.toIntOrNull() == null -> "Неверный индекс."
                    postIndexStr.toInt() !in allPosts.indices -> "Нет поста с таким индексом."
                    else -> null
                }

                call.respondHtml {
                    body {
                        if (error != null) {
                            p { +"Ошибка: $error" }
                        } else {
                            val post = allPosts[postIndexStr!!.toInt()]
                            likePost(post, user!!)
                            p { +"Лайк добавлен." }
                        }
                        a("/feed") { +"Назад к ленте" }
                    }
                }
            }

            // Создание пользователя:
            get("/createUser") {
                call.respondHtml {
                    body {
                        h2 { +"Создать пользователя" }
                        form(action = "/createUser", method = FormMethod.post) {
                            textInput(name = "userName") { placeholder = "Имя" }
                            br
                            submitInput { value = "Создать" }
                        }
                        br
                        a("/") { +"На главную" }
                    }
                }
            }

            post("/createUser") {
                val params = call.receiveParameters()
                val name = params["userName"]?.trim() ?: ""
                val error = when {
                    name.isBlank() -> "Имя пользователя не может быть пустым."
                    findUserByName(allUsers, name) != null -> "Пользователь с таким именем уже существует."
                    else -> null
                }

                call.respondHtml {
                    body {
                        if (error != null) {
                            p { +"Ошибка: $error" }
                        } else {
                            allUsers.add(User(name))
                            p { +"Пользователь $name создан." }
                        }
                        a("/") { +"На главную" }
                    }
                }
            }

            // Создание поста:
            get("/createPost") {
                call.respondHtml {
                    body {
                        h2 { +"Создать пост" }
                        if (allUsers.isEmpty()) {
                            p { +"Нет ни одного пользователя." }
                            a("/") { +"На главную" }
                        } else {
                            form(action = "/createPost", method = FormMethod.post) {
                                p {
                                    +"Автор поста:"
                                    br
                                    textInput(name = "author") {
                                        placeholder = "Автор поста"
                                        size = "30"
                                    }
                                }
                                p {
                                    +"Заголовок:"
                                    br
                                    textInput(name = "title") {
                                        placeholder = "Заголовок"
                                        size = "50"
                                    }
                                }
                                p {
                                    +"Тема:"
                                    br
                                    select {
                                        attributes["name"] = "topic"
                                        option {
                                            attributes["value"] = "Новости"
                                            +"Новости"
                                        }
                                        option {
                                            attributes["value"] = "Спорт"
                                            +"Спорт"
                                        }
                                        option {
                                            attributes["value"] = "Технологии"
                                            +"Технологии"
                                        }
                                        option {
                                            attributes["value"] = "Развлечения"
                                            +"Развлечения"
                                        }
                                    }
                                }
                                p {
                                    +"Содержимое поста:"
                                    br
                                    textArea {
                                        attributes["name"] = "content"
                                        attributes["rows"] = "10"
                                        attributes["cols"] = "50"
                                    }
                                }
                                br
                                submitInput { value = "Создать пост" }
                            }
                            a("/") { +"На главную" }
                        }
                    }
                }
            }

            post("/createPost") {
                val params = call.receiveParameters()
                val authorName = params["author"]?.trim() ?: ""
                val title = params["title"]?.trim() ?: ""
                val topic = params["topic"]?.trim() ?: ""
                val content = params["content"]?.trim() ?: ""
                val user = findUserByName(allUsers, authorName)
                val error = when {
                    allUsers.isEmpty() -> "Нет ни одного пользователя."
                    user == null -> "Пользователь не найден."
                    title.isBlank() -> "Заголовок не может быть пустым."
                    topic.isBlank() -> "Тема не выбрана."
                    content.isBlank() -> "Содержимое поста не может быть пустым."
                    else -> null
                }

                call.respondHtml {
                    body {
                        if (error != null) {
                            p { +"Ошибка: $error" }
                        } else {
                            createPost(user!!, title, topic, content, dateProvider)
                            p { +"Пост создан." }
                        }
                        a("/") { +"На главную" }
                    }
                }
            }

            // Создание комментария в общем виде:
            get("/createComment") {
                call.respondHtml {
                    body {
                        h2 { +"Создать комментарий" }
                        if (allUsers.isEmpty()) {
                            p { +"Нет пользователей." }
                            a("/") { +"На главную" }
                        } else {
                            val allPosts = mutableListOf<Post>()
                            for (u in allUsers) for (p in u.posts) allPosts.add(p)

                            if (allPosts.isEmpty()) {
                                p { +"Нет постов для комментирования." }
                                a("/") { +"На главную" }
                            } else {
                                form(action = "/createComment", method = FormMethod.post) {
                                    textInput(name = "commenter") { placeholder = "Комментатор" }
                                    br
                                    p { +"Выберите пост по индексу:" }
                                    for (i in allPosts.indices) {
                                        p { +"$i: ${allPosts[i].author.name}: \"${allPosts[i].content}\"" }
                                    }
                                    textInput(name = "postIndex") { placeholder = "Индекс поста" }
                                    br
                                    textInput(name = "commentContent") { placeholder = "Текст комментария" }
                                    br
                                    submitInput { value = "Добавить комментарий" }
                                }
                                a("/") { +"На главную" }
                            }
                        }
                    }
                }
            }

            post("/createComment") {
                val params = call.receiveParameters()
                val commenterName = params["commenter"]?.trim() ?: ""
                val postIndexStr = params["postIndex"]?.trim()
                val commentContent = params["commentContent"]?.trim() ?: ""
                val commenter = findUserByName(allUsers, commenterName)

                val allPosts = mutableListOf<Post>()
                for (u in allUsers) for (p in u.posts) allPosts.add(p)

                val error = when {
                    allUsers.isEmpty() -> "Нет пользователей."
                    commenter == null -> "Комментатор не найден."
                    allPosts.isEmpty() -> "Нет постов."
                    postIndexStr == null -> "Не указан индекс поста."
                    postIndexStr.toIntOrNull() == null -> "Неверный индекс поста."
                    postIndexStr.toInt() !in allPosts.indices -> "Нет поста с таким индексом."
                    commentContent.isBlank() -> "Комментарий не может быть пустым."
                    else -> null
                }

                call.respondHtml {
                    body {
                        if (error != null) {
                            p { +"Ошибка: $error" }
                        } else {
                            val post = allPosts[postIndexStr!!.toInt()]
                            createComment(commenter!!, post, commentContent, dateProvider)
                            p { +"Комментарий добавлен." }
                        }
                        a("/") { +"На главную" }
                    }
                }
            }

            // Лайк поста в общем виде:
            get("/likePost") {
                call.respondHtml {
                    body {
                        h2 { +"Поставить лайк посту" }
                        if (allUsers.isEmpty()) {
                            p { +"Нет пользователей." }
                            a("/") { +"На главную" }
                        } else {
                            val allPosts = mutableListOf<Post>()
                            for (u in allUsers) for (p in u.posts) allPosts.add(p)

                            if (allPosts.isEmpty()) {
                                p { +"Нет постов для лайков." }
                                a("/") { +"На главную" }
                            } else {
                                form(action = "/likePost", method = FormMethod.post) {
                                    textInput(name = "user") { placeholder = "Имя пользователя, который лайкает" }
                                    br
                                    p { +"Выберите пост по индексу:" }
                                    for (i in allPosts.indices) {
                                        p { +"$i: ${allPosts[i].author.name}: \"${allPosts[i].content}\"" }
                                    }
                                    textInput(name = "postIndex") { placeholder = "Индекс поста" }
                                    br
                                    submitInput { value = "Лайкнуть" }
                                }
                                a("/") { +"На главную" }
                            }
                        }
                    }
                }
            }

            post("/likePost") {
                val params = call.receiveParameters()
                val userName = params["user"]?.trim() ?: ""
                val postIndexStr = params["postIndex"]?.trim()

                val user = findUserByName(allUsers, userName)
                val allPosts = mutableListOf<Post>()
                for (u in allUsers) for (p in u.posts) allPosts.add(p)

                val error = when {
                    allUsers.isEmpty() -> "Нет пользователей."
                    user == null -> "Пользователь не найден."
                    allPosts.isEmpty() -> "Нет постов."
                    postIndexStr == null -> "Не указан индекс поста."
                    postIndexStr.toIntOrNull() == null -> "Неверный индекс."
                    postIndexStr.toInt() !in allPosts.indices -> "Нет поста с таким индексом."
                    else -> null
                }

                call.respondHtml {
                    body {
                        if (error != null) {
                            p { +"Ошибка: $error" }
                        } else {
                            val post = allPosts[postIndexStr!!.toInt()]
                            likePost(post, user!!)
                            p { +"Лайк добавлен." }
                        }
                        a("/") { +"На главную" }
                    }
                }
            }

            // Добавление друзей:
            get("/addFriend") {
                call.respondHtml {
                    body {
                        h2 { +"Добавить друга" }
                        if (allUsers.size < 2) {
                            p { +"Недостаточно пользователей для добавления друзей." }
                            a("/") { +"На главную" }
                        } else {
                            form(action = "/addFriend", method = FormMethod.post) {
                                textInput(name = "user1") { placeholder = "Имя первого пользователя" }
                                br
                                textInput(name = "user2") { placeholder = "Имя второго пользователя" }
                                br
                                submitInput { value = "Добавить в друзья" }
                            }
                            a("/") { +"На главную" }
                        }
                    }
                }
            }

            post("/addFriend") {
                val params = call.receiveParameters()
                val u1 = params["user1"]?.trim() ?: ""
                val u2 = params["user2"]?.trim() ?: ""
                val user1 = findUserByName(allUsers, u1)
                val user2 = findUserByName(allUsers, u2)
                val error = when {
                    allUsers.size < 2 -> "Недостаточно пользователей."
                    user1 == null -> "Пользователь $u1 не найден."
                    user2 == null -> "Пользователь $u2 не найден."
                    else -> null
                }

                call.respondHtml {
                    body {
                        if (error != null) {
                            p { +"Ошибка: $error" }
                        } else {
                            addFriend(user1!!, user2!!)
                            p { +"Пользователи теперь друзья." }
                        }
                        a("/") { +"На главную" }
                    }
                }
            }

            // Популярные посты (только отображение):
            get("/popularPosts") {
                call.respondHtml {
                    body {
                        h2 { +"Популярные посты" }
                        val popular = findPopularPosts(allUsers)
                        if (popular.isEmpty()) {
                            p { +"Нет постов." }
                        } else {
                            for (p in popular) {
                                p { +"${p.author.name}'s post: \"${p.content}\" [Лайков: ${p.likes.size}, Комментов: ${p.comments.size}]" }
                            }
                        }
                        a("/") { +"На главную" }
                    }
                }
            }

            // Анализ активности (только отображение):
            get("/activity") {
                val stats = getTopicsStats()
                if (stats.isEmpty()) {
                    call.respondHtml {
                        body {
                            h2 { +"Диаграмма популярности тем" }
                            p { +"Нет постов для анализа." }
                            a("/") { +"На главную" }
                        }
                    }
                    return@get
                }

                // Определяем максимальное число лайков, чтобы нормировать ширину полосы
                val maxLikes = stats.maxOf { it.third }.coerceAtLeast(1)

                call.respondHtml {
                    head {
                        title { +"Диаграмма популярности тем" }
                        style {
                            +"""
                .bar-container {
                    width: 400px; 
                    background: #eee; 
                    border: 1px solid #ccc; 
                    margin: 5px 0; 
                    position: relative;
                }
                .bar {
                    background: #4CAF50; 
                    height: 20px; 
                }
                """.trimIndent()
                        }
                    }
                    body {
                        h2 { +"Диаграмма популярности тем" }
                        p { +"Показывает отношение количества постов и лайков по темам." }

                        table {
                            tr {
                                th { +"Тема" }
                                th { +"Кол-во постов" }
                                th { +"Лайков всего" }
                                th { +"Диаграмма (по лайкам)" }
                            }

                            for ((topic, postsCount, likesCount) in stats) {
                                tr {
                                    td { +topic }
                                    td { +"$postsCount" }
                                    td { +"$likesCount" }

                                    val barWidth = (likesCount.toDouble() / maxLikes * 300).toInt()
                                    td {
                                        div("bar-container") {
                                            div("bar") {
                                                style = "width: ${barWidth}px;"
                                            }
                                        }
                                    }
                                }
                            }
                        }

                        br
                        a("/") { +"На главную" }
                    }
                }
            }

            // Рекомендации друзей:
            get("/recommend") {
                call.respondHtml {
                    body {
                        h2 { +"Рекомендация друзей" }
                        if (allUsers.isEmpty()) {
                            p { +"Нет пользователей." }
                            a("/") { +"На главную" }
                        } else {
                            form(action = "/recommend", method = FormMethod.post) {
                                textInput(name = "user") { placeholder = "Имя пользователя для рекомендаций" }
                                br
                                submitInput { value = "Показать рекомендации" }
                            }
                            a("/") { +"На главную" }
                        }
                    }
                }
            }

            post("/recommend") {
                val params = call.receiveParameters()
                val uname = params["user"]?.trim() ?: ""
                val user = findUserByName(allUsers, uname)
                val error = when {
                    allUsers.isEmpty() -> "Нет пользователей."
                    user == null -> "Пользователь не найден."
                    else -> null
                }

                call.respondHtml {
                    body {
                        if (error != null) {
                            p { +"Ошибка: $error" }
                        } else {
                            val recs = recommendFriends(user!!, allUsers)
                            if (recs.isEmpty()) {
                                p { +"Нет рекомендаций для ${user.name}" }
                            } else {
                                p { +"Рекомендуемые друзья для ${user.name}: ${recs.joinToString { it.name }}" }
                            }
                        }
                        a("/") { +"На главную" }
                    }
                }
            }

            get("/viewComments") {
                val postIndexStr = call.request.queryParameters["postIndex"]
                val allPosts = mutableListOf<Post>()
                for (u in allUsers) for (p in u.posts) allPosts.add(p)

                call.respondHtml {
                    body {
                        h2 { +"Комментарии к посту" }
                        val error = when {
                            allPosts.isEmpty() -> "Нет постов."
                            postIndexStr == null -> "Не указан индекс поста."
                            postIndexStr.toIntOrNull() == null -> "Неверный индекс поста."
                            postIndexStr.toInt() !in allPosts.indices -> "Нет поста с таким индексом."
                            else -> null
                        }

                        if (error != null) {
                            p { +"Ошибка: $error" }
                            a("/feed") { +"Назад к ленте" }
                        } else {
                            val post = allPosts[postIndexStr!!.toInt()]
                            p { +"Пост: \"${post.title}\" (Автор: ${post.author.name})" }
                            if (post.comments.isEmpty()) {
                                p { +"Комментариев нет." }
                            } else {
                                for (c in post.comments) {
                                    div {
                                        p { +"Автор комментария: ${c.author.name}" }
                                        p { +"Дата: ${c.date}" }
                                        p { +"Текст: ${c.content}" }
                                    }
                                    hr {}
                                }
                            }
                            a("/feed") { +"Назад к ленте" }
                        }
                    }
                }
            }
        }
    }.start(wait = true)
}
