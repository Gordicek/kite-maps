package app.kite.core

import kotlin.test.*

class GooglePlaceMediaTests {
    @Test fun structurallyReadsNestedPanoramaAndTileLevels() {
        val node = Json.parse("""[[1],[2,"AbCdEfGhIjKlMnOpQrStUv"],[null,null,null,[[[[256,512]],[[512,1024]],[[1024,2048]]],[512,512]]],null,null,[[null,[[null,null,50.087,14.421],null,[252.5]]]]]""")
        val direct = StreetView.parse(Json.write(listOf(listOf(0), node)))
        val byId = StreetView.parse(")]}'\n" + Json.write(listOf(listOf(0), listOf(node))))
        assertEquals(direct, byId)
        val p = assertNotNull(direct)
        assertEquals(2048 to 1024, p.size(2))
        assertEquals(512, p.tileSize)
        assertEquals(252.5, p.heading)
        assertEquals(LatLng(50.087, 14.421), p.location)
    }

    @Test fun separatesGalleryPhotosFromAvatarsAndStreetView() {
        val paths = listOf("https://lh3.googleusercontent.com/gps-cs-s/photoA=w400-h400",
            "https://lh3.googleusercontent.com/gps-cs-s/photoA=w80-h80",
            "https://lh4.googleusercontent.com/p/AF1QipPhotoB=s640",
            "https://lh3.googleusercontent.com/a/Avatar",
            "https://streetviewpixels-pa.googleapis.com/v1/thumbnail?panoid=AbCdEfGhIjKlMnOpQrStUv&yaw=22")
        assertEquals(listOf("https://lh3.googleusercontent.com/gps-cs-s/photoA=w800-h600",
            "https://lh4.googleusercontent.com/p/AF1QipPhotoB=w800-h600"), GooglePlaceMedia.imageUrls(paths, 10))
        assertEquals(StreetView.Hint("AbCdEfGhIjKlMnOpQrStUv", 22.0), GooglePlaceMedia.streetViewHint(paths))
        val row = listOf("wrb.fr", "hspqX", Json.write(listOf(paths)))
        val envelope = ")]}'\n\n100\n" + Json.write(listOf(row))
        assertEquals(2, GooglePlaceMedia.photos(envelope).size)
    }

    @Test fun parsesReviewsAndUsesUnsignedGoogleFeatureIds() {
        val json = Json.parse("""[null,null,[[[null,"Dana",null],"yesterday",null,"Good coffee",5],[[null,"Alex"],"last week",null,null,4]]]""")
        val reviews = GooglePlaceMedia.reviews(json)
        assertEquals(listOf("Dana", "Alex"), reviews.map { it.author })
        assertEquals("Good coffee", reviews.first().text)
        assertNull(reviews.last().text)
        assertContains(assertNotNull(GooglePlaceMedia.reviewParameter("0x1:0xffffffffffffffff")), "!2y18446744073709551615")
        assertEquals("18446744073709551615", GooglePlaceMedia.cid("0x1:0xffffffffffffffff"))
        assertNull(GooglePlaceMedia.photoRequest("not-a-feature-id"))
    }

    @Test fun panoramaLinksKeepNearbyWalksAndExcludeHistoricalImagery() {
        val node = Json.parse("""[[1],[2,"AbCdEfGhIjKlMnOpQrStUv"],null,null,null,[[null,
          [[null,null,50.087,14.421],null,[252.5]],null,
          [[[[2,"firstWalkPanorama12345"],null,[[null,null,50.08709,14.421]]],
            [[2,"nextWalkPanorama123456"],null,[[null,null,50.0872,14.421]]],
            [[2,"olderViewPanorama12345"],null,[[null,null,50.087,14.42115]]]]],null,null,null,null,
          [[2,[2020,1]]]]]]""")
        val p = assertNotNull(StreetView.parse(Json.write(listOf(listOf(0), node))))
        val link = p.links.single()
        assertEquals("firstWalkPanorama12345", link.id)
        assertEquals(0.0, link.bearing, 0.001)
    }


    @Test fun publicPageAttachesOnlyContributorPhotosToTheirReview() {
        val rows = """[{"author":"Dana","rating":5,"text":"Good coffee","photos":[
            "https://lh3.googleusercontent.com/a/Avatar",
            "https://lh3.googleusercontent.com/grass-cs/CustomerPhoto=k-no"
        ]},{"author":"Alex","rating":4,"photos":[]}]"""
        val (first, second) = GooglePlaceMedia.publicPageReviews(rows)
        assertEquals(listOf("https://lh3.googleusercontent.com/grass-cs/CustomerPhoto=w800-h600"), first.photos)
        assertTrue(second.photos.isEmpty())
    }


    @Test fun publicPageReadsTheRatingDistributionAndContributorMetadata() {
        val page = GooglePlaceMedia.publicReviewDetails("""{
            "histogram":[1,2,7,23,180],
            "reviews":[{"id":"a","author":"Dana","rating":5,"likes":12,
                "avatar":"https://lh3.googleusercontent.com/a/Avatar"},
                {"author":"Ignored host","rating":4,"likes":-2,"avatar":"https://example.com/a/Other"}]
        }""")
        assertEquals(listOf(1, 2, 7, 23, 180), page.histogram)
        assertEquals("a", page.reviews.first().id)
        assertEquals(12, page.reviews.first().likes)
        assertEquals("https://lh3.googleusercontent.com/a/Avatar", page.reviews.first().avatar)
        assertNull(page.reviews.last().avatar)
        assertNull(page.reviews.last().likes)
    }

    @Test fun popularTimesIgnoreInvalidHoursAndDeduplicateTheProviderRows() {
        val days = GooglePlaceMedia.popularTimes(Json.parse("""[[[7,[[15,92],[24,80],[16,57],[15,11]]],[0,[[9,20]]],[1,[[8,5],[9,125]]]]]"""))
        assertEquals(listOf(BusyDay(1, listOf(BusyHour(8, 5))), BusyDay(7, listOf(BusyHour(15, 92), BusyHour(16, 57)))), days)
    }

}
