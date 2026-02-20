## 15. Open Questions

- [x] ~~Timezones: How to handle daily goals across timezones?~~ **RESOLVED:** Store period_start as DATE in user's local timezone
- [x] ~~Soft deletes: Should we preserve deleted goal history?~~ **RESOLVED:** Yes, use soft deletes (deleted_at)
- [x] ~~Input validation: How to validate metric_type-specific values?~~ **RESOLVED:** Zod schemas with metric_type checking
- [x] ~~N+1 queries: How to fetch group data efficiently?~~ **RESOLVED:** Use JOINs or bulk queries with in-memory grouping
- [ ] Email service: Resend vs SendGrid vs AWS SES?
- [ ] WebSocket: Should we add real-time updates beyond FCM?
- [ ] Caching strategy: Redis vs in-memory LRU cache?
- [ ] Background jobs: Bull vs Agenda vs native Cloud Tasks?
- [ ] File uploads: Where to store avatars? (Cloud Storage)
- [ ] Analytics: Track API usage, popular features?
- [ ] Backup strategy: Daily snapshots vs continuous archiving?

---

**End of Backend Server Specification**

**Version:** 1.0  
**Status:** Ready for Implementation  
**Estimated Development Time:** 6-8 weeks
