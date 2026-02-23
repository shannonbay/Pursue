Write a spec for public group listings for Pursue.  Group admins can set a group as a public listing or private.  This setting should be available on group or challenge creation (with a tooltip - this allows others to see your group's name, membership count, activity score and goals but not your activity log).  They can also configure group spot limits (optional - unlimited or maybe a realistic limit of 500 is an alternative).  Pursue needs a dashboard where users can browse public groups with category filters and see group categories, heat scores, membership counts, number of active goals, and then expand to see the exact goal details including title and active days.  Users should also be scored on their activity level, so they can be vetted before being admitted to a group.  Users should be able to submit a note with their group join request, which will help with the vetting process.  New Pursue users should receive a reasonable score and/or a new user badge, which helps eliminate bias against first-timers.



In addition, users in inactive groups (based on a group heat threshold - see the group-heat-spec.md) receive group suggestions based on pgvector analysis of goal titles and active days similarities. The suggested groups should be public listings with free spots above a given threshold of group heat.  The suggested groups don't need to be an exact match, but a partial match for the group they're in, for example at least one goal has a high similarity score)



If a new user doesn't have an invite code, the orientation flow should quiz them on categories they're interested in and suggest public groups with high heat and goals matching the chosen categories.  They can skip to the next section, Challenges, if they click "I'd rather create my own group"



Since Pursue will now be connecting people who don't know each other in real life, it needs to provide a way for them to communicate eg about admin decisions.  Therefore admins should be able to set a discord, whatsapp or telegram group link in the group settings, which is visible only to group members.  That solves the communication problem without building comms infrastructure.