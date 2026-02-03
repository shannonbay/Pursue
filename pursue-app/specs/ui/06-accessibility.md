## 6. Accessibility

### 6.1 Requirements

**WCAG 2.1 Level AA:**
- Color contrast ratio ≥ 4.5:1 for text
- Touch targets ≥ 48dp × 48dp
- Screen reader support (TalkBack)
- Dynamic text sizing

### 6.2 Colorblind Support

- Blue and gold palette optimized for deuteranopia/protanopia
- No reliance on color alone for information
- Icons and text labels supplement color coding
- Heatmap uses intensity (opacity) not just color
- Progress indicators show percentage text alongside bar

### 6.3 Screen Reader Support (TalkBack)

**Content Descriptions:**

*Goal Cards:*
- Card body (not logged): "Run 3 times per week. 2 of 3 complete this week. Tap to log progress."
- Card body (logged today - binary): "Run 3 times per week. Logged today. Tap to remove log."
- Card body (logged today - numeric): "Run 3 times per week. 5.2 miles logged today. Tap to edit."
- Arrow button: "View goal details"
- After logging: Announce "Progress logged for Run 3 times per week"
- After removing: Announce "Progress removed for Run 3 times per week"

*Dialogs:*
- Confirmation dialog: "Alert. Remove today's log? This will unlog your progress for today. Cancel button. Remove button."
- Edit dialog: "Dialog. Edit Progress. Miles, 5.2. Note, optional. Delete Entry button. Cancel button. Save button."
- Delete confirmation: "Alert. Delete this entry? Cancel button. Delete button."

*FABs:*
- Goals Tab FAB (admin only): "Add goal"
- Members Tab FAB (everyone): "Invite members"
- Long-press FAB: Shows tooltip with label

*Other Elements:*
- Progress bars: "30 min run, completed today"
- Member avatars: "Shannon Thompson, last active 2 hours ago"
- Heatmap cells: "January 15th, 4 of 5 goals completed"
- Empty states: Full description with CTA button labeled

**Semantic Markup:**
- Use heading hierarchy (h1, h2, h3) for sections
- Group related controls (RadioGroup for Yes/No)
- Mark decorative images as decorative
- Goal cards use proper clickable regions with distinct labels
- FAB changes announced when switching tabs

**Navigation:**
- Bottom nav items read as "Home, tab 1 of 3, selected"
- Group tabs read as "Goals, tab 1 of 3, selected"
- Two distinct touch zones on goal cards:
  - Large area: "Tap to log progress"
  - Small arrow: "View details"
- FAB icon change announces new action when tabs switch
- Swipe gestures for next/previous item work correctly

**Implementation:**
```kotlin
// Goal card accessibility
goalCard.contentDescription = 
    "$title. $progress. Tap to log progress."
    
detailsArrow.contentDescription = "View goal details"

// After logging
announceForAccessibility("Progress logged for $title")

// FAB updates on tab switch
fab.contentDescription = when (tab) {
    Tab.GOALS -> "Add goal"
    Tab.MEMBERS -> "Invite members"
    else -> null
}

// Announce FAB change
if (previousFAB != null && currentFAB != null) {
    announceForAccessibility("Button changed to ${fab.contentDescription}")
}
```

### 6.4 Dynamic Text Sizing

**Support up to 200% text size:**
- All text wraps properly, no truncation
- UI adapts to larger text (cards expand vertically)
- Touch targets maintain 48dp minimum even with large text
- Test at 100%, 150%, 200% scale

### 6.5 Haptic Feedback

**Appropriate Use:**
- Goal logged successfully: Medium impact (one tap)
- Goal completed (binary "Yes"): Medium impact + celebration
- Button press: Light impact (optional, can be disabled)
- Error: Warning haptic pattern
- Settings toggle to disable all haptics

**Accessibility Note:**
- Never rely on haptics alone for critical feedback
- Always pair with visual confirmation (toast, animation)

---

