Issue 1: API "Draft" Releases Lock Up the Web UI
**Description:** When an App Bundle is uploaded to a test track via the Play Developer API with `status: "draft"`, it creates a "ghost" release. If a developer later navigates to the Play Console Web UI and attempts to "Create new release" on that same track, the UI becomes locked. Attempting to include the API-uploaded bundle results in: *"The release does not add or remove any app bundles."* Attempting to promote or edit it results in: *"The release cannot be rolled out because it does not allow existing users to upgrade..."*
**Impact:** Developers are forced to use the API to explicitly clear the track's releases (`body: {"releases": []}`) just to unblock the Web UI and regain the ability to create releases manually.

---

Issue 2: Opaque "Precondition check failed" API Errors
**Description:** When committing an edit via the API (`edits().commit()`), if the app is missing setup requirements (e.g., Content Rating, Data Safety, App Signing), the API throws a generic `Precondition check failed` error. 
**Impact:** There is no indication of *which* precondition failed. CI/CD pipelines cannot parse this to inform developers what needs to be fixed. The API should return a specific, actionable error (e.g., "Precondition check failed: Content Rating questionnaire incomplete").

---

Issue 3: Misleading UI in "Not Included" Bundles
**Description:** In the Release creation Web UI, under the "Not included from previous releases" section, there is a prominent right-facing arrow (→) next to the bundle name. Users intuitively click this arrow expecting it to move the bundle into the active release. Instead, it opens an "Explore App Bundle" modal. 
**Impact:** The actual "Include" text button is easily overlooked, leading to severe user frustration as developers loop through the Explore modal unable to figure out how to attach their bundle to the release.

---

Issue 4: Programmatic Country Availability Updates via API
**Description:** The `edits.countryavailability` API endpoint currently only supports `get`. It needs to support `update` and `patch` methods.
**Impact:** When automating deployments to Closed Testing (Alpha) tracks via CI/CD, the entire pipeline fails because country targeting must be configured manually in the Web UI first. Fully automated CI/CD requires the ability to set track country targeting programmatically.

---

Issue 5: "Upload to Artifact Library" API Endpoint
**Description:** Provide a direct API endpoint to upload an AAB/APK straight to the App Bundle Library *without* requiring an active track edit or release assignment.
**Impact:** Teams often want their CI to build and upload artifacts to the Play Console automatically, but prefer to manually construct the actual releases in the UI later using those pre-uploaded artifacts. Forcing an upload to be tied to a track edit unnecessarily complicates this separation of concerns and leads to UI lock-ups.
