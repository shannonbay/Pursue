# Group Progress Export Specification

**Version:** 1.0  
**Last Updated:** February 4, 2026  
**Status:** Implementation Ready

---

## 1. Overview

### 1.1 Purpose

This specification defines the API endpoint and implementation for exporting comprehensive group progress reports as professionally formatted Excel workbooks. Each report provides a complete overview of member progress across all goals for a specified time period.

### 1.2 Use Cases

- **Group Leaders**: Review member engagement and identify struggling members
- **Personal Analysis**: Members analyze their own performance trends
- **Milestone Reports**: Document achievements for quarterly/annual reviews
- **Data Portability**: Export data for external analysis or backup

### 1.3 Technology

**Library:** ExcelJS (v4.4.0+)
- TypeScript support
- Professional styling (fonts, colors, borders, conditional formatting)
- Multi-worksheet support
- Merged cells for calendar layouts
- Export as binary buffer or stream (no file system required)

**Installation:**
```bash
npm install exceljs
npm install --save-dev @types/exceljs
```

---

## 2. API Endpoint

### 2.1 Export Group Progress Report

#### GET /api/groups/:group_id/export-progress

Generate Excel workbook with progress overview for all group members.

**Headers:**
```
Authorization: Bearer {access_token}
```

**Query Parameters:**
```typescript
{
  start_date: string;     // YYYY-MM-DD (required)
  end_date: string;       // YYYY-MM-DD (required)
  user_timezone: string;  // IANA timezone, e.g., "America/New_York" (required)
}
```

**Validation:**
- `group_id`: Valid UUID, group must exist
- `start_date`: ISO date format, not in future
- `end_date`: ISO date format, must be >= start_date, not in future
- `user_timezone`: Valid IANA timezone string
- Date range: Maximum 24 months (730 days)

**Response (200 OK):**
```
Content-Type: application/vnd.openxmlformats-officedocument.spreadsheetml.sheet
Content-Disposition: attachment; filename="Morning_Runners_Progress_2025-01-01_to_2026-01-01.xlsx"
Transfer-Encoding: chunked

[Binary Excel file data]
```

**Authorization:**
- User must be a member of the group with status='approved'

**Rate Limiting:**
- 10 requests per hour per user (generation is CPU-intensive)

**Server Logic:**

1. **Authorization & Validation**
   - Verify JWT token
   - Confirm user is approved member of group
   - Validate date range (max 24 months)
   - Validate timezone format

2. **Data Retrieval (Optimized Queries)**
   ```typescript
   // Single query: Fetch group with members and goals
   const groupData = await db
     .selectFrom('groups')
     .innerJoin('group_memberships', 'groups.id', 'group_memberships.group_id')
     .innerJoin('users', 'group_memberships.user_id', 'users.id')
     .leftJoin('goals', (join) => join
       .onRef('goals.group_id', '=', 'groups.id')
       .on('goals.deleted_at', 'is', null) // Only active goals
     )
     .select([
       'groups.id as group_id',
       'groups.name as group_name',
       'users.id as user_id',
       'users.display_name',
       'users.email',
       'goals.id as goal_id',
       'goals.title as goal_title',
       'goals.cadence',
       'goals.metric_type',
       'goals.target_value',
       'goals.unit'
     ])
     .where('groups.id', '=', groupId)
     .where('group_memberships.status', '=', 'approved')
     .orderBy('users.display_name')
     .orderBy('goals.created_at')
     .execute();
   
   // Single query: Fetch all progress for period
   const progressData = await db
     .selectFrom('progress_entries')
     .innerJoin('goals', 'progress_entries.goal_id', 'goals.id')
     .select([
       'progress_entries.goal_id',
       'progress_entries.user_id',
       'progress_entries.value',
       'progress_entries.period_start',
       'goals.cadence',
       'goals.metric_type',
       'goals.target_value'
     ])
     .where('goals.group_id', '=', groupId)
     .where('progress_entries.period_start', '>=', startDate)
     .where('progress_entries.period_start', '<=', endDate)
     .execute();
   ```

3. **Data Processing**
   - Group data by user
   - Calculate completion percentages per goal per user
   - Build calendar matrices for each user
   - Generate monthly summaries

4. **Excel Generation**
   - Create workbook with one worksheet per member
   - Apply professional styling (see Section 3)
   - Generate binary buffer
   - Stream to response

5. **Response**
   - Set appropriate headers
   - Stream Excel buffer to client
   - Log export event for audit trail

**Errors:**
- 400: Invalid date range or timezone
- 403: User not an approved member of group
- 404: Group not found
- 429: Rate limit exceeded
- 500: Export generation failed

**Performance Considerations:**
- Use streaming to handle large datasets
- Limit concurrent exports per server instance
- Consider async queue for very large groups (50+ members)
- Cache goal/member data to avoid repeated queries

**Example Request:**
```bash
curl -H "Authorization: Bearer {token}" \
  "https://api.getpursue.app/api/groups/550e8400-e29b-41d4-a716-446655440000/export-progress?start_date=2025-01-01&end_date=2026-01-01&user_timezone=America/New_York" \
  --output progress_report.xlsx
```

---

## 3. Excel Workbook Format

### 3.1 Workbook Structure

**Workbook-Level Properties:**
```typescript
workbook.creator = 'Pursue';
workbook.created = new Date();
workbook.modified = new Date();
workbook.properties = {
  title: `${groupName} Progress Report`,
  subject: `Progress from ${startDate} to ${endDate}`,
  keywords: 'pursue, goals, progress, accountability'
};
```

**Worksheets:**
- One worksheet per approved group member
- Sheet name: User's display name (sanitized, max 31 chars)
- Sheet order: Alphabetical by display name

---

### 3.2 Worksheet Layout (Per Member)

Each worksheet contains two main sections:
1. **Summary Section** (Rows 1-N)
2. **Calendar Section** (Rows N+2 onwards, grouped by month)

---

### 3.3 Summary Section

**Layout:**

```
┌──────────────────────────────────────────────────────────────┐
│  SHANNON THOMPSON - PROGRESS SUMMARY                         │ Row 1 (Title)
│  Morning Runners | Jan 1, 2025 - Jan 1, 2026                 │ Row 2 (Subtitle)
├──────────────────────────────────────────────────────────────┤
│                                                               │ Row 3 (Blank)
│  Goal                    Cadence    Completion   Days/Times   │ Row 4 (Headers)
├──────────────────────────┬──────────┬───────────┬────────────┤
│  30 min run              │ Daily    │   67.3%   │ 246/365    │ Row 5
│  Read 50 pages           │ Daily    │   82.1%   │ 300/365    │ Row 6
│  Team meeting            │ Weekly   │   91.5%   │  48/52     │ Row 7
│  Write blog post         │ Monthly  │   75.0%   │   9/12     │ Row 8
├──────────────────────────┴──────────┴───────────┴────────────┤
│                                                               │ Row 9 (Blank)
│  YEARLY GOALS                                                 │ Row 10 (Header)
├──────────────────────────┬──────────┬───────────┬────────────┤
│  Run marathon            │ Yearly   │ Complete  │   ✓        │ Row 11
│  Read 52 books           │ Yearly   │  Pending  │   -        │ Row 12
└──────────────────────────┴──────────┴───────────┴────────────┘
```

**Implementation:**

```typescript
import ExcelJS from 'exceljs';

// Title Row
const titleRow = worksheet.getRow(1);
titleRow.height = 24;
const titleCell = titleRow.getCell(1);
titleCell.value = `${displayName.toUpperCase()} - PROGRESS SUMMARY`;
titleCell.font = { name: 'Segoe UI', size: 16, bold: true, color: { argb: 'FF1565C0' } };
titleCell.alignment = { vertical: 'middle', horizontal: 'left' };

// Subtitle Row
const subtitleRow = worksheet.getRow(2);
subtitleRow.height = 18;
const subtitleCell = subtitleRow.getCell(1);
subtitleCell.value = `${groupName} | ${formatDate(startDate)} - ${formatDate(endDate)}`;
subtitleCell.font = { name: 'Segoe UI', size: 11, color: { argb: 'FF616161' } };
subtitleCell.alignment = { vertical: 'middle', horizontal: 'left' };

// Header Row (Row 4)
const headerRow = worksheet.getRow(4);
headerRow.height = 22;
headerRow.values = ['Goal', 'Cadence', 'Completion', 'Days/Times'];
headerRow.font = { name: 'Segoe UI', size: 11, bold: true, color: { argb: 'FFFFFFFF' } };
headerRow.fill = {
  type: 'pattern',
  pattern: 'solid',
  fgColor: { argb: 'FF1565C0' } // Pursue blue
};
headerRow.alignment = { vertical: 'middle', horizontal: 'center' };
headerRow.border = {
  bottom: { style: 'medium', color: { argb: 'FF1565C0' } }
};

// Column Widths
worksheet.getColumn(1).width = 30; // Goal
worksheet.getColumn(2).width = 12; // Cadence
worksheet.getColumn(3).width = 14; // Completion
worksheet.getColumn(4).width = 14; // Days/Times

// Data Rows (Daily, Weekly, Monthly goals)
let currentRow = 5;
for (const goal of dailyWeeklyMonthlyGoals) {
  const row = worksheet.getRow(currentRow);
  row.height = 20;
  
  const completedCount = calculateCompletedCount(goal, progressData);
  const totalPossible = calculateTotalPossible(goal.cadence, startDate, endDate);
  const percentage = (completedCount / totalPossible * 100).toFixed(1);
  
  row.values = [
    goal.title,
    capitalizeFirst(goal.cadence),
    `${percentage}%`,
    `${completedCount}/${totalPossible}`
  ];
  
  row.font = { name: 'Segoe UI', size: 10 };
  row.alignment = { vertical: 'middle' };
  
  // Left-align goal title
  row.getCell(1).alignment = { vertical: 'middle', horizontal: 'left' };
  
  // Center-align other columns
  row.getCell(2).alignment = { vertical: 'middle', horizontal: 'center' };
  row.getCell(3).alignment = { vertical: 'middle', horizontal: 'center' };
  row.getCell(4).alignment = { vertical: 'middle', horizontal: 'center' };
  
  // Conditional formatting for completion percentage
  const completionCell = row.getCell(3);
  const percentValue = parseFloat(percentage);
  if (percentValue >= 80) {
    completionCell.font = { ...completionCell.font, color: { argb: 'FF2E7D32' }, bold: true }; // Green
  } else if (percentValue >= 60) {
    completionCell.font = { ...completionCell.font, color: { argb: 'FFF57C00' }, bold: true }; // Orange
  } else {
    completionCell.font = { ...completionCell.font, color: { argb: 'FFC62828' }, bold: true }; // Red
  }
  
  // Alternate row background
  if ((currentRow - 5) % 2 === 0) {
    row.fill = {
      type: 'pattern',
      pattern: 'solid',
      fgColor: { argb: 'FFF5F5F5' }
    };
  }
  
  // Light borders
  row.eachCell((cell) => {
    cell.border = {
      top: { style: 'thin', color: { argb: 'FFE0E0E0' } },
      bottom: { style: 'thin', color: { argb: 'FFE0E0E0' } }
    };
  });
  
  currentRow++;
}

// Blank row
currentRow++;

// Yearly Goals Section
const yearlyHeaderRow = worksheet.getRow(currentRow);
yearlyHeaderRow.height = 22;
yearlyHeaderRow.getCell(1).value = 'YEARLY GOALS';
yearlyHeaderRow.getCell(1).font = { name: 'Segoe UI', size: 12, bold: true, color: { argb: 'FF1565C0' } };
yearlyHeaderRow.getCell(1).alignment = { vertical: 'middle', horizontal: 'left' };
currentRow++;

for (const goal of yearlyGoals) {
  const row = worksheet.getRow(currentRow);
  row.height = 20;
  
  const hasProgress = checkYearlyGoalProgress(goal, progressData, startDate, endDate);
  
  row.values = [
    goal.title,
    'Yearly',
    hasProgress ? 'Complete' : 'Pending',
    hasProgress ? '✓' : '-'
  ];
  
  row.font = { name: 'Segoe UI', size: 10 };
  row.alignment = { vertical: 'middle' };
  row.getCell(1).alignment = { vertical: 'middle', horizontal: 'left' };
  row.getCell(2).alignment = { vertical: 'middle', horizontal: 'center' };
  row.getCell(3).alignment = { vertical: 'middle', horizontal: 'center' };
  row.getCell(4).alignment = { vertical: 'middle', horizontal: 'center' };
  
  // Color code status
  const statusCell = row.getCell(3);
  if (hasProgress) {
    statusCell.font = { ...statusCell.font, color: { argb: 'FF2E7D32' }, bold: true };
  } else {
    statusCell.font = { ...statusCell.font, color: { argb: 'FF757575' } };
  }
  
  // Alternate row background
  if ((currentRow - yearlyStartRow) % 2 === 0) {
    row.fill = {
      type: 'pattern',
      pattern: 'solid',
      fgColor: { argb: 'FFF5F5F5' }
    };
  }
  
  currentRow++;
}
```

**Calculation Helpers:**

```typescript
/**
 * Calculate total possible completions for a goal in a date range
 */
function calculateTotalPossible(
  cadence: 'daily' | 'weekly' | 'monthly' | 'yearly',
  startDate: string,
  endDate: string
): number {
  const start = new Date(startDate);
  const end = new Date(endDate);
  
  switch (cadence) {
    case 'daily':
      const daysDiff = Math.floor((end.getTime() - start.getTime()) / (1000 * 60 * 60 * 24));
      return daysDiff + 1; // Inclusive
      
    case 'weekly':
      // Count full weeks + partial weeks
      const weeksDiff = Math.ceil((end.getTime() - start.getTime()) / (1000 * 60 * 60 * 24 * 7));
      return weeksDiff;
      
    case 'monthly':
      let months = 0;
      let current = new Date(start);
      while (current <= end) {
        months++;
        current.setMonth(current.getMonth() + 1);
      }
      return months;
      
    case 'yearly':
      return end.getFullYear() - start.getFullYear() + 1;
  }
}

/**
 * Calculate completed count for a goal
 */
function calculateCompletedCount(
  goal: Goal,
  progressData: ProgressEntry[]
): number {
  const goalProgress = progressData.filter(p => p.goal_id === goal.id && p.user_id === goal.user_id);
  
  switch (goal.metric_type) {
    case 'binary':
      // Count entries where value = 1
      return goalProgress.filter(p => p.value === 1).length;
      
    case 'numeric':
      // Count entries where value >= target_value
      return goalProgress.filter(p => p.value >= goal.target_value).length;
      
    case 'duration':
      // Count entries where value >= target_value (seconds)
      return goalProgress.filter(p => p.value >= goal.target_value).length;
  }
}

/**
 * Check if yearly goal has any progress in period
 */
function checkYearlyGoalProgress(
  goal: Goal,
  progressData: ProgressEntry[],
  startDate: string,
  endDate: string
): boolean {
  const goalProgress = progressData.filter(p => 
    p.goal_id === goal.id && 
    p.user_id === goal.user_id &&
    p.value > 0 // Any positive value counts as progress
  );
  return goalProgress.length > 0;
}
```

---

### 3.4 Calendar Section

**Layout (Example: January 2025):**

```
┌────────────────────────────────────────────────────────────────────────────┐
│  JANUARY 2025                                                              │ Month Header
├─────┬─────┬─────┬─────┬─────┬─────┬─────┬─────┬──────────────────────────┤
│ Sun │ Mon │ Tue │ Wed │ Thu │ Fri │ Sat │     │ Weekly Goal              │ Day Headers
├─────┼─────┼─────┼─────┼─────┼─────┼─────┼─────┼──────────────────────────┤
│     │     │     │  1  │  2  │  3  │  4  │     │                          │ Dates Row 1
│     │     │     │  ✓  │  ✓  │  -  │  ✓  │  ✓  │ Team meeting             │ Goal Row 1a (Weekly)
│     │     │     │  ✓  │  -  │  -  │  ✓  │     │ 30 min run               │ Goal Row 1b (Daily)
│     │     │     │  ✓  │  ✓  │  ✓  │  ✓  │     │ Read 50 pages            │ Goal Row 1c (Daily)
├─────┼─────┼─────┼─────┼─────┼─────┼─────┼─────┼──────────────────────────┤
│  5  │  6  │  7  │  8  │  9  │ 10  │ 11  │     │                          │ Dates Row 2
│  ✓  │  -  │  ✓  │  ✓  │  ✓  │  ✓  │  ✓  │  ✓  │ Team meeting             │ Goal Row 2a (Weekly)
│  ✓  │  ✓  │  ✓  │  ✓  │  ✓  │  ✓  │  ✓  │     │ 30 min run               │ Goal Row 2b (Daily)
│  ✓  │  ✓  │  ✓  │  ✓  │  -  │  ✓  │  ✓  │     │ Read 50 pages            │ Goal Row 2c (Daily)
├─────┴─────┴─────┴─────┴─────┴─────┴─────┴─────┴──────────────────────────┤
│  ... (more weeks) ...                                                     │
├───────────────────────────────────────────────────────────────────────────┤
│  JANUARY SUMMARY                                                          │ Month Summary
│  30 min run: 22/31 days (71.0%)                                           │
│  Read 50 pages: 26/31 days (83.9%)                                        │
│  Team meeting: 4/4 weeks (100.0%)                                         │
└───────────────────────────────────────────────────────────────────────────┘
```

**Implementation:**

```typescript
/**
 * Generate calendar section for all months in range
 */
function generateCalendarSection(
  worksheet: ExcelJS.Worksheet,
  startRow: number,
  goals: Goal[],
  progressData: ProgressEntry[],
  startDate: string,
  endDate: string,
  userTimezone: string
): void {
  const start = new Date(startDate);
  const end = new Date(endDate);
  
  let currentRow = startRow;
  
  // Iterate through each month
  let currentMonth = new Date(start.getFullYear(), start.getMonth(), 1);
  
  while (currentMonth <= end) {
    currentRow = generateMonthCalendar(
      worksheet,
      currentRow,
      goals,
      progressData,
      currentMonth,
      startDate,
      endDate,
      userTimezone
    );
    
    currentMonth.setMonth(currentMonth.getMonth() + 1);
    currentRow += 2; // Blank row between months
  }
}

/**
 * Generate calendar for a single month
 */
function generateMonthCalendar(
  worksheet: ExcelJS.Worksheet,
  startRow: number,
  goals: Goal[],
  progressData: ProgressEntry[],
  monthDate: Date,
  overallStart: string,
  overallEnd: string,
  userTimezone: string
): number {
  let currentRow = startRow;
  
  // Filter to daily and weekly goals only (monthly/yearly not shown in calendar)
  const calendarGoals = goals.filter(g => g.cadence === 'daily' || g.cadence === 'weekly');
  
  if (calendarGoals.length === 0) {
    return currentRow; // Skip month if no calendar goals
  }
  
  // Month Header
  const monthHeaderRow = worksheet.getRow(currentRow);
  monthHeaderRow.height = 24;
  worksheet.mergeCells(currentRow, 1, currentRow, 9);
  const monthHeaderCell = monthHeaderRow.getCell(1);
  monthHeaderCell.value = monthDate.toLocaleDateString('en-US', { 
    month: 'long', 
    year: 'numeric' 
  }).toUpperCase();
  monthHeaderCell.font = { name: 'Segoe UI', size: 14, bold: true, color: { argb: 'FF1565C0' } };
  monthHeaderCell.alignment = { vertical: 'middle', horizontal: 'left' };
  monthHeaderCell.fill = {
    type: 'pattern',
    pattern: 'solid',
    fgColor: { argb: 'FFE3F2FD' } // Light blue background
  };
  currentRow++;
  
  // Day Headers Row
  const dayHeaderRow = worksheet.getRow(currentRow);
  dayHeaderRow.height = 20;
  dayHeaderRow.values = ['Sun', 'Mon', 'Tue', 'Wed', 'Thu', 'Fri', 'Sat', '', 'Weekly Goal'];
  dayHeaderRow.font = { name: 'Segoe UI', size: 10, bold: true, color: { argb: 'FFFFFFFF' } };
  dayHeaderRow.fill = {
    type: 'pattern',
    pattern: 'solid',
    fgColor: { argb: 'FF1565C0' }
  };
  dayHeaderRow.alignment = { vertical: 'middle', horizontal: 'center' };
  
  // Set column widths
  for (let col = 1; col <= 7; col++) {
    worksheet.getColumn(col).width = 6;
  }
  worksheet.getColumn(8).width = 2; // Spacer
  worksheet.getColumn(9).width = 24; // Goal names
  
  currentRow++;
  
  // Build calendar grid
  const monthStart = new Date(monthDate.getFullYear(), monthDate.getMonth(), 1);
  const monthEnd = new Date(monthDate.getFullYear(), monthDate.getMonth() + 1, 0);
  
  const firstDayOfWeek = monthStart.getDay(); // 0 = Sunday
  const daysInMonth = monthEnd.getDate();
  
  let dayOfMonth = 1;
  let weekStartRow = currentRow;
  
  while (dayOfMonth <= daysInMonth) {
    // Date Row
    const dateRow = worksheet.getRow(currentRow);
    dateRow.height = 18;
    const dateValues: (number | string)[] = ['', '', '', '', '', '', '', '', ''];
    
    for (let dayOfWeek = 0; dayOfWeek < 7 && dayOfMonth <= daysInMonth; dayOfWeek++) {
      if (currentRow === weekStartRow && dayOfWeek < firstDayOfWeek) {
        dateValues[dayOfWeek] = ''; // Empty cells before first day
      } else if (dayOfMonth <= daysInMonth) {
        dateValues[dayOfWeek] = dayOfMonth;
        dayOfMonth++;
      }
    }
    
    dateRow.values = dateValues;
    dateRow.font = { name: 'Segoe UI', size: 9, bold: true };
    dateRow.alignment = { vertical: 'middle', horizontal: 'center' };
    dateRow.fill = {
      type: 'pattern',
      pattern: 'solid',
      fgColor: { argb: 'FFFAFAFA' } // Very light gray
    };
    
    // Border for date cells
    for (let col = 1; col <= 7; col++) {
      dateRow.getCell(col).border = {
        top: { style: 'thin', color: { argb: 'FFBDBDBD' } },
        left: { style: 'thin', color: { argb: 'FFBDBDBD' } },
        right: { style: 'thin', color: { argb: 'FFBDBDBD' } },
        bottom: { style: 'thin', color: { argb: 'FFBDBDBD' } }
      };
    }
    
    currentRow++;
    
    // Goal Rows for this week
    for (let goalIdx = 0; goalIdx < calendarGoals.length; goalIdx++) {
      const goal = calendarGoals[goalIdx];
      const goalRow = worksheet.getRow(currentRow);
      goalRow.height = 18;
      
      const goalValues: string[] = ['', '', '', '', '', '', '', '', ''];
      
      // Fill in checkmarks/dashes for each day
      let weekDayIndex = 0;
      for (let col = 1; col <= 7; col++) {
        const dateValue = dateRow.getCell(col).value;
        if (dateValue && typeof dateValue === 'number') {
          const currentDate = new Date(monthDate.getFullYear(), monthDate.getMonth(), dateValue);
          const dateString = currentDate.toISOString().split('T')[0];
          
          // Check if date is within overall range
          if (dateString >= overallStart && dateString <= overallEnd) {
            const hasProgress = checkProgressForDate(goal, progressData, dateString);
            goalValues[col - 1] = hasProgress ? '✓' : '-';
          }
        }
        weekDayIndex++;
      }
      
      // Weekly goal completion indicator
      if (goal.cadence === 'weekly') {
        const weekStart = getWeekStart(dateRow, monthDate);
        const weekHasProgress = weekStart ? checkWeeklyProgress(goal, progressData, weekStart) : false;
        goalValues[8] = weekHasProgress ? '✓' : '-';
      } else {
        goalValues[8] = goal.title; // Show goal name for daily goals
      }
      
      goalRow.values = goalValues;
      goalRow.font = { name: 'Segoe UI', size: 9 };
      goalRow.alignment = { vertical: 'middle', horizontal: 'center' };
      
      // Style checkmarks and dashes
      for (let col = 1; col <= 9; col++) {
        const cell = goalRow.getCell(col);
        if (cell.value === '✓') {
          cell.font = { ...cell.font, color: { argb: 'FF2E7D32' }, bold: true }; // Green
        } else if (cell.value === '-') {
          cell.font = { ...cell.font, color: { argb: 'FFBDBDBD' } }; // Gray
        }
        
        // Borders for day cells
        if (col <= 7) {
          cell.border = {
            left: { style: 'thin', color: { argb: 'FFE0E0E0' } },
            right: { style: 'thin', color: { argb: 'FFE0E0E0' } },
            bottom: { style: 'thin', color: { argb: 'FFE0E0E0' } }
          };
        }
      }
      
      // Goal name in column 9 for weekly goals
      if (goal.cadence === 'weekly') {
        const nameCell = goalRow.getCell(9);
        nameCell.value = goal.title;
        nameCell.alignment = { vertical: 'middle', horizontal: 'left' };
        nameCell.font = { name: 'Segoe UI', size: 9, italic: true };
      }
      
      currentRow++;
    }
    
    weekStartRow = currentRow;
  }
  
  // Month Summary Section
  currentRow++;
  const summaryHeaderRow = worksheet.getRow(currentRow);
  summaryHeaderRow.height = 20;
  worksheet.mergeCells(currentRow, 1, currentRow, 9);
  const summaryHeaderCell = summaryHeaderRow.getCell(1);
  summaryHeaderCell.value = `${monthDate.toLocaleDateString('en-US', { month: 'long' }).toUpperCase()} SUMMARY`;
  summaryHeaderCell.font = { name: 'Segoe UI', size: 11, bold: true, color: { argb: 'FF1565C0' } };
  summaryHeaderCell.alignment = { vertical: 'middle', horizontal: 'left' };
  summaryHeaderCell.fill = {
    type: 'pattern',
    pattern: 'solid',
    fgColor: { argb: 'FFF5F5F5' }
  };
  currentRow++;
  
  // Calculate monthly stats for each goal
  for (const goal of calendarGoals) {
    const summaryRow = worksheet.getRow(currentRow);
    summaryRow.height = 18;
    worksheet.mergeCells(currentRow, 1, currentRow, 9);
    
    const monthlyStats = calculateMonthlyStats(goal, progressData, monthDate, overallStart, overallEnd);
    
    const summaryCell = summaryRow.getCell(1);
    summaryCell.value = `${goal.title}: ${monthlyStats.completed}/${monthlyStats.total} ${goal.cadence === 'weekly' ? 'weeks' : 'days'} (${monthlyStats.percentage}%)`;
    summaryCell.font = { name: 'Segoe UI', size: 10 };
    summaryCell.alignment = { vertical: 'middle', horizontal: 'left', indent: 1 };
    
    // Color code percentage
    if (monthlyStats.percentageValue >= 80) {
      summaryCell.font = { ...summaryCell.font, color: { argb: 'FF2E7D32' } };
    } else if (monthlyStats.percentageValue >= 60) {
      summaryCell.font = { ...summaryCell.font, color: { argb: 'FFF57C00' } };
    } else {
      summaryCell.font = { ...summaryCell.font, color: { argb: 'FFC62828' } };
    }
    
    currentRow++;
  }
  
  return currentRow;
}

/**
 * Check if user has progress for a specific date
 */
function checkProgressForDate(
  goal: Goal,
  progressData: ProgressEntry[],
  dateString: string
): boolean {
  const entry = progressData.find(p => 
    p.goal_id === goal.id && 
    p.user_id === goal.user_id &&
    p.period_start === dateString
  );
  
  if (!entry) return false;
  
  switch (goal.metric_type) {
    case 'binary':
      return entry.value === 1;
    case 'numeric':
      return entry.value >= goal.target_value;
    case 'duration':
      return entry.value >= goal.target_value;
    default:
      return false;
  }
}

/**
 * Check if user has progress for a weekly goal
 */
function checkWeeklyProgress(
  goal: Goal,
  progressData: ProgressEntry[],
  weekStartDate: string
): boolean {
  const entry = progressData.find(p => 
    p.goal_id === goal.id && 
    p.user_id === goal.user_id &&
    p.period_start === weekStartDate
  );
  
  if (!entry) return false;
  
  switch (goal.metric_type) {
    case 'binary':
      return entry.value === 1;
    case 'numeric':
      return entry.value >= goal.target_value;
    case 'duration':
      return entry.value >= goal.target_value;
    default:
      return false;
  }
}

/**
 * Get Monday of the week for a date row
 */
function getWeekStart(dateRow: ExcelJS.Row, monthDate: Date): string | null {
  // Find the Monday (column 2) in this week
  const mondayValue = dateRow.getCell(2).value;
  if (mondayValue && typeof mondayValue === 'number') {
    const monday = new Date(monthDate.getFullYear(), monthDate.getMonth(), mondayValue);
    return monday.toISOString().split('T')[0];
  }
  return null;
}

/**
 * Calculate monthly statistics for a goal
 */
function calculateMonthlyStats(
  goal: Goal,
  progressData: ProgressEntry[],
  monthDate: Date,
  overallStart: string,
  overallEnd: string
): { completed: number; total: number; percentage: string; percentageValue: number } {
  const monthStart = new Date(monthDate.getFullYear(), monthDate.getMonth(), 1);
  const monthEnd = new Date(monthDate.getFullYear(), monthDate.getMonth() + 1, 0);
  
  // Constrain to overall range
  const effectiveStart = new Date(Math.max(monthStart.getTime(), new Date(overallStart).getTime()));
  const effectiveEnd = new Date(Math.min(monthEnd.getTime(), new Date(overallEnd).getTime()));
  
  if (goal.cadence === 'daily') {
    const daysInRange = Math.floor((effectiveEnd.getTime() - effectiveStart.getTime()) / (1000 * 60 * 60 * 24)) + 1;
    
    let completed = 0;
    for (let d = new Date(effectiveStart); d <= effectiveEnd; d.setDate(d.getDate() + 1)) {
      const dateString = d.toISOString().split('T')[0];
      if (checkProgressForDate(goal, progressData, dateString)) {
        completed++;
      }
    }
    
    const percentageValue = (completed / daysInRange * 100);
    return {
      completed,
      total: daysInRange,
      percentage: percentageValue.toFixed(1),
      percentageValue
    };
  } else if (goal.cadence === 'weekly') {
    // Count weeks that overlap with this month
    let weeksInRange = 0;
    let completed = 0;
    
    let currentWeekStart = getMonday(effectiveStart);
    const finalWeekStart = getMonday(effectiveEnd);
    
    while (currentWeekStart <= finalWeekStart) {
      weeksInRange++;
      const weekStartString = currentWeekStart.toISOString().split('T')[0];
      if (checkWeeklyProgress(goal, progressData, weekStartString)) {
        completed++;
      }
      currentWeekStart.setDate(currentWeekStart.getDate() + 7);
    }
    
    const percentageValue = weeksInRange > 0 ? (completed / weeksInRange * 100) : 0;
    return {
      completed,
      total: weeksInRange,
      percentage: percentageValue.toFixed(1),
      percentageValue
    };
  }
  
  return { completed: 0, total: 0, percentage: '0.0', percentageValue: 0 };
}

/**
 * Get Monday of the week containing the given date
 */
function getMonday(date: Date): Date {
  const d = new Date(date);
  const day = d.getDay();
  const diff = d.getDate() - day + (day === 0 ? -6 : 1); // Adjust when day is Sunday
  return new Date(d.setDate(diff));
}
```

---

### 3.5 Styling Summary

**Color Palette:**
- Primary Blue: `#1565C0` (Pursue brand color)
- Success Green: `#2E7D32`
- Warning Orange: `#F57C00`
- Error Red: `#C62828`
- Text Gray: `#616161`
- Border Gray: `#E0E0E0`
- Background Light Gray: `#F5F5F5`
- Background Very Light Gray: `#FAFAFA`
- Light Blue Background: `#E3F2FD`

**Typography:**
- Font: Segoe UI (fallback: Arial, sans-serif)
- Title: 16pt bold
- Subtitle: 11pt regular
- Headers: 11-14pt bold
- Body: 9-10pt regular

**Cell Formatting:**
- Header rows: Bold white text on blue background
- Alternate rows: Light gray background for readability
- Conditional formatting: Green (≥80%), Orange (≥60%), Red (<60%)
- Borders: Thin gray borders for structure
- Alignment: Left for text, center for numbers/symbols

---

## 4. Implementation Details

### 4.1 Complete Express Route Handler

```typescript
import { Router, Request, Response } from 'express';
import ExcelJS from 'exceljs';
import { z } from 'zod';
import { db } from '../services/database';
import { authenticateJWT } from '../middleware/auth';

const router = Router();

// Validation schema
const exportQuerySchema = z.object({
  start_date: z.string().regex(/^\d{4}-\d{2}-\d{2}$/),
  end_date: z.string().regex(/^\d{4}-\d{2}-\d{2}$/),
  user_timezone: z.string().min(1)
}).refine((data) => {
  const start = new Date(data.start_date);
  const end = new Date(data.end_date);
  const now = new Date();
  
  // Validate dates are not in future
  if (start > now || end > now) {
    throw new Error('Dates cannot be in the future');
  }
  
  // Validate end >= start
  if (end < start) {
    throw new Error('end_date must be >= start_date');
  }
  
  // Validate max 24 months
  const daysDiff = (end.getTime() - start.getTime()) / (1000 * 60 * 60 * 24);
  if (daysDiff > 730) {
    throw new Error('Date range cannot exceed 24 months (730 days)');
  }
  
  return true;
});

router.get(
  '/groups/:group_id/export-progress',
  authenticateJWT,
  async (req: Request, res: Response) => {
    try {
      const { group_id } = req.params;
      const userId = req.user!.id;
      
      // Validate query parameters
      const { start_date, end_date, user_timezone } = exportQuerySchema.parse(req.query);
      
      // 1. Verify user is approved member of group
      const membership = await db
        .selectFrom('group_memberships')
        .select(['id', 'status'])
        .where('group_id', '=', group_id)
        .where('user_id', '=', userId)
        .executeTakeFirst();
      
      if (!membership) {
        return res.status(403).json({ error: 'Not a member of this group' });
      }
      
      if (membership.status !== 'approved') {
        return res.status(403).json({ error: 'Membership not approved' });
      }
      
      // 2. Fetch group data, members, and goals in optimized query
      const groupDataRaw = await db
        .selectFrom('groups')
        .innerJoin('group_memberships', 'groups.id', 'group_memberships.group_id')
        .innerJoin('users', 'group_memberships.user_id', 'users.id')
        .leftJoin('goals', (join) => join
          .onRef('goals.group_id', '=', 'groups.id')
          .on('goals.deleted_at', 'is', null)
        )
        .select([
          'groups.id as group_id',
          'groups.name as group_name',
          'users.id as user_id',
          'users.display_name',
          'users.email',
          'goals.id as goal_id',
          'goals.title as goal_title',
          'goals.cadence',
          'goals.metric_type',
          'goals.target_value',
          'goals.unit',
          'goals.created_at as goal_created_at'
        ])
        .where('groups.id', '=', group_id)
        .where('group_memberships.status', '=', 'approved')
        .orderBy('users.display_name')
        .orderBy('goals.created_at')
        .execute();
      
      if (groupDataRaw.length === 0) {
        return res.status(404).json({ error: 'Group not found' });
      }
      
      // 3. Fetch all progress for the period
      const progressData = await db
        .selectFrom('progress_entries')
        .innerJoin('goals', 'progress_entries.goal_id', 'goals.id')
        .select([
          'progress_entries.goal_id',
          'progress_entries.user_id',
          'progress_entries.value',
          'progress_entries.period_start',
          'goals.cadence',
          'goals.metric_type',
          'goals.target_value'
        ])
        .where('goals.group_id', '=', group_id)
        .where('progress_entries.period_start', '>=', start_date)
        .where('progress_entries.period_start', '<=', end_date)
        .execute();
      
      // 4. Structure data by user
      const groupName = groupDataRaw[0].group_name;
      const memberMap = new Map<string, {
        id: string;
        display_name: string;
        email: string;
        goals: Goal[];
      }>();
      
      for (const row of groupDataRaw) {
        if (!memberMap.has(row.user_id)) {
          memberMap.set(row.user_id, {
            id: row.user_id,
            display_name: row.display_name,
            email: row.email,
            goals: []
          });
        }
        
        if (row.goal_id) {
          const member = memberMap.get(row.user_id)!;
          // Avoid duplicates
          if (!member.goals.find(g => g.id === row.goal_id)) {
            member.goals.push({
              id: row.goal_id,
              title: row.goal_title,
              cadence: row.cadence,
              metric_type: row.metric_type,
              target_value: row.target_value,
              unit: row.unit,
              user_id: row.user_id
            });
          }
        }
      }
      
      // 5. Generate Excel workbook
      const workbook = new ExcelJS.Workbook();
      workbook.creator = 'Pursue';
      workbook.created = new Date();
      workbook.modified = new Date();
      workbook.properties = {
        title: `${groupName} Progress Report`,
        subject: `Progress from ${start_date} to ${end_date}`,
        keywords: 'pursue, goals, progress, accountability'
      };
      
      // Create worksheet for each member
      const members = Array.from(memberMap.values()).sort((a, b) => 
        a.display_name.localeCompare(b.display_name)
      );
      
      for (const member of members) {
        const sheetName = sanitizeSheetName(member.display_name);
        const worksheet = workbook.addWorksheet(sheetName);
        
        // Generate summary section
        let currentRow = 1;
        currentRow = generateSummarySection(
          worksheet,
          currentRow,
          member,
          groupName,
          member.goals,
          progressData,
          start_date,
          end_date
        );
        
        // Generate calendar section
        currentRow += 2; // Spacing
        generateCalendarSection(
          worksheet,
          currentRow,
          member.goals,
          progressData.filter(p => p.user_id === member.id),
          start_date,
          end_date,
          user_timezone
        );
      }
      
      // 6. Stream workbook to response
      const filename = `${sanitizeFilename(groupName)}_Progress_${start_date}_to_${end_date}.xlsx`;
      
      res.setHeader(
        'Content-Type',
        'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet'
      );
      res.setHeader(
        'Content-Disposition',
        `attachment; filename="${filename}"`
      );
      
      await workbook.xlsx.write(res);
      res.end();
      
      // 7. Log export event
      await db
        .insertInto('group_activities')
        .values({
          group_id: group_id,
          user_id: userId,
          activity_type: 'export_progress',
          activity_data: JSON.stringify({ start_date, end_date })
        })
        .execute();
      
    } catch (error) {
      console.error('Export progress error:', error);
      
      if (error instanceof z.ZodError) {
        return res.status(400).json({ error: 'Invalid query parameters', details: error.errors });
      }
      
      return res.status(500).json({ error: 'Failed to generate progress report' });
    }
  }
);

export default router;
```

### 4.2 Helper Functions

```typescript
/**
 * Sanitize sheet name (Excel has strict requirements)
 */
function sanitizeSheetName(name: string): string {
  // Remove invalid characters: \ / * ? : [ ]
  let sanitized = name.replace(/[\\\/\*\?\:\[\]]/g, '');
  
  // Truncate to 31 characters (Excel limit)
  if (sanitized.length > 31) {
    sanitized = sanitized.substring(0, 31);
  }
  
  // Ensure not empty
  if (sanitized.length === 0) {
    sanitized = 'Sheet';
  }
  
  return sanitized;
}

/**
 * Sanitize filename for attachment
 */
function sanitizeFilename(name: string): string {
  // Replace spaces with underscores, remove special chars
  return name.replace(/[^a-zA-Z0-9_-]/g, '_').substring(0, 100);
}

/**
 * Format date for display
 */
function formatDate(dateString: string): string {
  const date = new Date(dateString);
  return date.toLocaleDateString('en-US', { 
    month: 'short', 
    day: 'numeric', 
    year: 'numeric' 
  });
}

/**
 * Capitalize first letter
 */
function capitalizeFirst(str: string): string {
  return str.charAt(0).toUpperCase() + str.slice(1);
}
```

### 4.3 TypeScript Interfaces

```typescript
interface Goal {
  id: string;
  title: string;
  cadence: 'daily' | 'weekly' | 'monthly' | 'yearly';
  metric_type: 'binary' | 'numeric' | 'duration';
  target_value: number | null;
  unit: string | null;
  user_id: string;
}

interface ProgressEntry {
  goal_id: string;
  user_id: string;
  value: number;
  period_start: string; // DATE string 'YYYY-MM-DD'
  cadence: string;
  metric_type: string;
  target_value: number | null;
}

interface Member {
  id: string;
  display_name: string;
  email: string;
  goals: Goal[];
}
```

---

## 5. Testing

### 5.1 Manual Testing Checklist

**Authorization:**
- [ ] Non-member returns 403
- [ ] Pending member returns 403
- [ ] Approved member returns 200

**Validation:**
- [ ] Missing start_date returns 400
- [ ] Invalid date format returns 400
- [ ] Future dates return 400
- [ ] end_date < start_date returns 400
- [ ] Date range > 24 months returns 400
- [ ] Invalid timezone returns 400

**Data Accuracy:**
- [ ] Summary percentages match manual calculation
- [ ] Calendar checkmarks match database entries
- [ ] Monthly summaries match calendar data
- [ ] Yearly goals show correct status
- [ ] Weekly goals show completion in correct column

**Excel Format:**
- [ ] File opens in Excel/Google Sheets/LibreOffice
- [ ] One sheet per member, alphabetically sorted
- [ ] Headers styled correctly (blue background, white text)
- [ ] Conditional formatting applies (green/orange/red)
- [ ] Month headers display properly
- [ ] Calendar grid aligns correctly
- [ ] Monthly summaries appear after each month

**Performance:**
- [ ] Small group (2 members, 3 goals, 1 month) < 2 seconds
- [ ] Medium group (10 members, 10 goals, 6 months) < 5 seconds
- [ ] Large group (50 members, 20 goals, 12 months) < 15 seconds

### 5.2 Sample Test Data

```sql
-- Insert test group
INSERT INTO groups (id, name, description, creator_user_id)
VALUES ('test-group-uuid', 'Test Group', 'For export testing', 'user-1-uuid');

-- Insert test members
INSERT INTO group_memberships (group_id, user_id, status, role)
VALUES 
  ('test-group-uuid', 'user-1-uuid', 'approved', 'creator'),
  ('test-group-uuid', 'user-2-uuid', 'approved', 'member'),
  ('test-group-uuid', 'user-3-uuid', 'approved', 'member');

-- Insert test goals
INSERT INTO goals (id, group_id, title, cadence, metric_type, target_value, unit)
VALUES
  ('goal-1-uuid', 'test-group-uuid', 'Daily Run', 'daily', 'binary', NULL, NULL),
  ('goal-2-uuid', 'test-group-uuid', 'Read 50 Pages', 'daily', 'numeric', 50, 'pages'),
  ('goal-3-uuid', 'test-group-uuid', 'Team Meeting', 'weekly', 'binary', NULL, NULL),
  ('goal-4-uuid', 'test-group-uuid', 'Blog Post', 'monthly', 'binary', NULL, NULL),
  ('goal-5-uuid', 'test-group-uuid', 'Run Marathon', 'yearly', 'binary', NULL, NULL);

-- Insert test progress (sample)
INSERT INTO progress_entries (goal_id, user_id, value, period_start, user_timezone)
VALUES
  -- User 1: Daily run - completed 200/365 days
  ('goal-1-uuid', 'user-1-uuid', 1, '2025-01-01', 'America/New_York'),
  ('goal-1-uuid', 'user-1-uuid', 1, '2025-01-02', 'America/New_York'),
  -- ... (generate programmatically for realistic data)
  
  -- User 1: Weekly team meeting - completed 48/52 weeks
  ('goal-3-uuid', 'user-1-uuid', 1, '2025-01-06', 'America/New_York'), -- Week starting Monday Jan 6
  ('goal-3-uuid', 'user-1-uuid', 1, '2025-01-13', 'America/New_York'),
  -- ...
  
  -- User 1: Yearly marathon goal
  ('goal-5-uuid', 'user-1-uuid', 1, '2025-11-01', 'America/New_York');
```

---

## 6. Deployment Considerations

### 6.1 Dependencies

Update `package.json`:
```json
{
  "dependencies": {
    "exceljs": "^4.4.0"
  },
  "devDependencies": {
    "@types/exceljs": "^1.3.0"
  }
}
```

### 6.2 Memory Considerations

**ExcelJS Memory Usage:**
- Small workbook (2 members, 3 months): ~5 MB
- Medium workbook (10 members, 12 months): ~25 MB
- Large workbook (50 members, 24 months): ~150 MB

**Cloud Run Configuration:**
```yaml
memory: 1Gi  # Increase from default 512Mi if handling large exports
cpu: 1       # Single CPU sufficient for most exports
timeout: 300s # 5 minutes for large exports
```

### 6.3 Rate Limiting

Implement in middleware:
```typescript
import rateLimit from 'express-rate-limit';

const exportLimiter = rateLimit({
  windowMs: 60 * 60 * 1000, // 1 hour
  max: 10, // 10 requests per hour per IP
  message: 'Too many export requests, please try again later',
  standardHeaders: true,
  legacyHeaders: false
});

router.get('/groups/:group_id/export-progress', exportLimiter, authenticateJWT, ...);
```

### 6.4 Monitoring

Track metrics:
- Export request rate
- Export generation time (by member count, date range)
- Memory usage during generation
- Error rate
- File size distribution

Log events:
```typescript
logger.info('Progress export started', {
  group_id,
  user_id: userId,
  start_date,
  end_date,
  member_count: members.length,
  goal_count: totalGoals
});

logger.info('Progress export completed', {
  group_id,
  user_id: userId,
  duration_ms: Date.now() - startTime,
  file_size_bytes: buffer.length,
  member_count: members.length
});
```

---

## 7. Future Enhancements

### 7.1 Potential Features

1. **Custom Date Presets**
   - Last 30 days
   - Last quarter
   - Year to date
   - Custom fiscal year

2. **Filtering Options**
   - Export specific members only
   - Export specific goals only
   - Include/exclude notes

3. **Additional Formats**
   - PDF export for printing
   - CSV export for data analysis
   - JSON export for programmatic access

4. **Advanced Visualizations**
   - Sparklines in summary section
   - Conditional formatting heatmaps
   - Trend charts per goal

5. **Scheduled Exports**
   - Monthly/quarterly automatic reports
   - Email delivery to group admins
   - Webhook notifications

6. **Comparison Reports**
   - Member vs. member comparison
   - Period vs. period comparison
   - Goal performance rankings

### 7.2 Alternative Layout (If Preferred)

Instead of one sheet per member, consider:
- **One sheet for summary** (all members side-by-side)
- **Separate sheets per goal** (all members' progress on that goal)
- **Combined calendar view** (all members in same calendar grid)

This alternative would reduce sheet count but make member-specific analysis harder.

---

## 8. Summary

This specification provides a complete implementation guide for generating professional Excel progress reports with:

✅ **Robust API endpoint** with validation and authorization  
✅ **Optimized database queries** to prevent N+1 problems  
✅ **Professional Excel formatting** with colors, borders, conditional formatting  
✅ **Comprehensive calendar view** with daily/weekly goal tracking  
✅ **Monthly summaries** with completion percentages  
✅ **Production-ready error handling** and rate limiting  
✅ **Scalable architecture** suitable for groups of all sizes  

The implementation uses ExcelJS for maximum flexibility and professional output quality, ensuring reports are immediately usable in Excel, Google Sheets, or LibreOffice without formatting issues.
