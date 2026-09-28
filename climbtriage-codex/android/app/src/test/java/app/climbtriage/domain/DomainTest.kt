package app.climbtriage.domain

import org.junit.Assert.*
import org.junit.Test

class DomainTest {
    private fun person(x:Float,y:Float=.5f)=Person(List(33) { i ->
        Landmark(x+(if(i%2==0) .02f else -.02f),y+(if(i>20) .12f else -.12f),1f,1f,true)
    })
    private fun frame(time:Long,vararg people:Person)=PoseFrame(time,time+4_000_000,people.toList())

    @Test fun letterboxRoundTripAndRejectBars() {
        val fit=Fit.within(1000f,600f,1080,1920)
        val p=Point(.25f,.75f)
        val actual=fit.normalized(fit.screen(p))!!
        assertEquals(p.x,actual.x,.00001f); assertEquals(p.y,actual.y,.00001f)
        assertNull(fit.normalized(Point(0f,200f)))
    }
    @Test fun crossingDoesNotSilentlySwitchPeople() {
        val frames=listOf(frame(0,person(.5f)),frame(70_000,person(.51f),person(.52f)),frame(140_000,person(.53f)))
        val track=Tracking.select(frames,listOf(Seed(0,0)),1f)
        assertNotNull(track[0].person)
        assertNull(track[1].person); assertNull(track[2].person)
    }
    @Test fun gapStaysLostUntilExplicitReselection() {
        val frames=listOf(frame(0,person(.5f)),frame(600_000,person(.5f)),frame(800_000,person(.5f)))
        val track=Tracking.select(frames,listOf(Seed(0,0),Seed(800_000,0)),1f)
        assertNull(track[1].person); assertNotNull(track[2].person)
    }
    @Test fun noFutureOrStalePoseOnVariableFrameRateReplay() {
        val frames=listOf(TrackedFrame(100_000,person(.5f),"id","associated"),TrackedFrame(410_000,null,"id","lost"))
        assertNull(Tracking.at(frames,99_999))
        assertNotNull(Tracking.at(frames,200_000)?.person)
        assertNull(Tracking.at(frames,300_000))
        assertNull(Tracking.at(frames,410_000)?.person)
    }
    @Test fun removalKeepsImmutableIdsAndClearsRouteReferences() {
        val hold=Hold(display_number=7,parts=listOf(listOf(Point(.1f,.1f),Point(.2f,.1f),Point(.2f,.2f))),timestamp_us=0)
        val wall=WallVersion(holds=listOf(hold))
        val route=RouteVersion(wall_version_id=wall.version_id,members=setOf(hold.id),starts=setOf(hold.id),finishes=setOf(hold.id))
        val old=Session(id="session",name="test",wall=wall,route=route)
        val next=Editor.change(old,emptyList(),"remove",listOf(hold.id),3_140_000)
        assertTrue(next.route.members.isEmpty()); assertTrue(next.route.starts.isEmpty()); assertTrue(next.route.finishes.isEmpty())
        assertEquals(hold.id,old.wall.holds.single().id)
        assertEquals(wall.version_id,next.wall.parent_version_id)
        assertNotEquals(route.version_id,next.route.version_id)
    }
    @Test fun selfIntersectingAndZeroAreaPolygonsFail() {
        assertFalse(Geometry.valid(listOf(Point(0f,0f),Point(1f,1f),Point(0f,1f),Point(1f,0f))))
        assertFalse(Geometry.valid(listOf(Point(0f,0f),Point(.5f,.5f),Point(1f,1f))))
        assertTrue(Geometry.valid(listOf(Point(.1f,.1f),Point(.3f,.1f),Point(.3f,.3f))))
    }
    @Test fun removingMembershipAlsoRemovesStartFinish() {
        val hold=Hold(display_number=1,parts=emptyList(),timestamp_us=0)
        val wall=WallVersion(holds=listOf(hold))
        val old=Session(id="s",name="n",wall=wall,route=RouteVersion(wall_version_id=wall.version_id,
            members=setOf(hold.id),starts=setOf(hold.id),finishes=setOf(hold.id)))
        val next=Editor.route(old,hold.id,"member",123)
        assertTrue(next.route.starts.isEmpty()); assertTrue(next.route.finishes.isEmpty())
    }
}
