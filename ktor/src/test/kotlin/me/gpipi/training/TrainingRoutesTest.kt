package me.gpipi.training

import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.cookies.HttpCookies
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.auth.authenticate
import io.ktor.server.config.MapApplicationConfig
import io.ktor.server.response.respond
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.server.sessions.sessions
import io.ktor.server.sessions.set
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import java.math.BigDecimal
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.gpipi.UserSession
import me.gpipi.configureSecurity
import me.gpipi.configureSerialization

class TrainingRoutesTest {
    private val service = mockk<TrainingService>()
    private val clock = Clock.fixed(Instant.parse("2026-08-10T03:00:00Z"), ZoneOffset.UTC)

    private fun ApplicationTestBuilder.boot() {
        environment { config = MapApplicationConfig("session.signKey" to "test-session-key") }
        application {
            configureSecurity(clock)
            configureSerialization()
            routing {
                post("/test/login") {
                    call.sessions.set(UserSession("U-web", clock.instant().epochSecond))
                    call.respond(HttpStatusCode.OK)
                }
                authenticate("auth-session") { trainingApiRoutes(service) }
            }
        }
    }

    private fun ApplicationTestBuilder.apiClient() = createClient {
        install(HttpCookies)
        install(ContentNegotiation) { json() }
    }

    @Test
    fun `GET overview uses authenticated owner and returns the selected authored week`() =
        testApplication {
            val programId = UUID.randomUUID()
            coEvery { service.overview("U-web", 3) } returns TrainingReadResult.Found(
                WeekOverviewRecord(
                    program = TrainingProgramRecord(programId, "M1", null, null, true),
                    currentWeekNumber = 3,
                    selectedWeekNumber = 3,
                    availableWeekNumbers = listOf(1, 2, 3),
                    workouts = emptyList(),
                ),
            )
            boot()
            val client = apiClient()
            client.post("/test/login")

            val response = client.get("/api/training?week=3")

            assertEquals(HttpStatusCode.OK, response.status)
            assertEquals("3", response.body<JsonObject>()["selectedWeekNumber"]?.jsonPrimitive?.content)
            coVerify(exactly = 1) { service.overview("U-web", 3) }
        }

    @Test
    fun `PUT set maps blank optional values without borrowing prescription targets`() =
        testApplication {
            val weekId = UUID.randomUUID()
            val prescriptionId = UUID.randomUUID()
            coEvery {
                service.putSet(
                    "U-web",
                    weekId,
                    prescriptionId,
                    1,
                    SetInput(11, null, BigDecimal("8.5"), null, null),
                )
            } returns TrainingMutationResult.Updated
            boot()
            val client = apiClient()
            client.post("/test/login")

            val response = client.put(
                "/api/training/weeks/$weekId/prescriptions/$prescriptionId/sets/1",
            ) {
                contentType(ContentType.Application.Json)
                setBody(PutSetRequest(reps = 11, load = "8.5"))
            }

            assertEquals(HttpStatusCode.NoContent, response.status)
            coVerify(exactly = 1) {
                service.putSet(
                    "U-web",
                    weekId,
                    prescriptionId,
                    1,
                    SetInput(11, null, BigDecimal("8.5"), null, null),
                )
            }
        }

    @Test
    fun `POST workout scopes manual authoring to one program week`() = testApplication {
        val programId = UUID.randomUUID()
        val exerciseId = UUID.randomUUID()
        val workoutId = UUID.randomUUID()
        val input = WorkoutCreateInput(
            name = "Full Body 1",
            groups = listOf(
                GroupAuthoringInput(
                    label = "A",
                    kind = "STRAIGHT_SET",
                    prescriptions = listOf(
                        PrescriptionAuthoringInput(
                            exerciseName = "Goblet squat",
                            exerciseId = exerciseId,
                            executionType = "REPS",
                            sets = "3",
                            reps = "10-12",
                        ),
                    ),
                ),
            ),
        )
        coEvery { service.createWorkout("U-web", programId, 1, input) } returns
            WorkoutCreateResult.Created(workoutId)
        boot()
        val client = apiClient()
        client.post("/test/login")

        val response = client.post("/api/training/programs/$programId/weeks/1/workouts") {
            contentType(ContentType.Application.Json)
            setBody(
                CreateWorkoutRequest(
                    name = "Full Body 1",
                    groups = listOf(
                        GroupAuthoringRequest(
                            label = "A",
                            kind = "STRAIGHT_SET",
                            prescriptions = listOf(
                                PrescriptionAuthoringRequest(
                                    exerciseName = "Goblet squat",
                                    exerciseId = exerciseId.toString(),
                                    executionType = "REPS",
                                    sets = "3",
                                    reps = "10-12",
                                ),
                            ),
                        ),
                    ),
                ),
            )
        }

        assertEquals(HttpStatusCode.Created, response.status)
        assertEquals(workoutId.toString(), response.body<JsonObject>()["id"]?.jsonPrimitive?.content)
        coVerify(exactly = 1) { service.createWorkout("U-web", programId, 1, input) }
    }

    @Test
    fun `PUT program updates only the authenticated program details`() = testApplication {
        val programId = UUID.randomUUID()
        val input = ProgramAuthoringInput(
            name = "M1 updated",
            note = "Adjusted block",
            startsOn = LocalDate.parse("2026-08-11"),
        )
        coEvery { service.updateProgram("U-web", programId, input) } returns TrainingMutationResult.Updated
        boot()
        val client = apiClient()
        client.post("/test/login")

        val response = client.put("/api/training/programs/$programId") {
            contentType(ContentType.Application.Json)
            setBody(CreateProgramRequest("M1 updated", "Adjusted block", "2026-08-11"))
        }

        assertEquals(HttpStatusCode.NoContent, response.status)
        coVerify(exactly = 1) { service.updateProgram("U-web", programId, input) }
    }

    @Test
    fun `foreign records remain indistinguishable from missing records at HTTP boundary`() =
        testApplication {
            val workoutId = UUID.randomUUID()
            coEvery { service.workoutDetail("U-web", 2, workoutId) } returns
                TrainingReadResult.NotFound
            boot()
            val client = apiClient()
            client.post("/test/login")

            val response = client.get("/api/training/weeks/2/workouts/$workoutId")

            assertEquals(HttpStatusCode.NotFound, response.status)
            assertEquals(
                "Training record not found.",
                response.body<JsonObject>()["message"]?.jsonPrimitive?.content,
            )
        }

    @Test
    fun `completed workout response includes preserved session timestamps`() =
        testApplication {
            val workoutId = UUID.randomUUID()
            val sessionId = UUID.randomUUID()
            val weekId = UUID.randomUUID()
            val now = OffsetDateTime.parse("2026-08-10T03:00:00Z")
            coEvery { service.workoutDetail("U-web", 2, workoutId) } returns TrainingReadResult.Found(
                WorkoutDetailRecord(
                    program = TrainingProgramRecord(UUID.randomUUID(), "M1", null, null, true),
                    currentWeekNumber = 3,
                    weekId = weekId,
                    weekNumber = 2,
                    skipped = false,
                    workoutId = workoutId,
                    workoutName = "Full Body 1",
                    workoutNote = null,
                    session = TrainingSessionRecord(
                        sessionId,
                        LocalDate.parse("2026-08-02"),
                        "COMPLETED",
                        null,
                        now,
                        now,
                    ),
                    groups = emptyList(),
                ),
            )
            boot()
            val client = apiClient()
            client.post("/test/login")

            val response = client.get("/api/training/weeks/2/workouts/$workoutId")

            assertEquals(HttpStatusCode.OK, response.status)
            val session = response.body<JsonObject>()["session"] as JsonObject
            assertEquals("COMPLETED", session["status"]?.jsonPrimitive?.content)
            assertEquals("2026-08-02", session["performedOn"]?.jsonPrimitive?.content)
        }
}
