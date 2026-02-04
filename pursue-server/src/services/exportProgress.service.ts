import type ExcelJS from 'exceljs';
import type { Worksheet } from 'exceljs';

export interface ExportGoal {
  id: string;
  title: string;
  cadence: 'daily' | 'weekly' | 'monthly' | 'yearly';
  metric_type: 'binary' | 'numeric' | 'duration';
  target_value: number | null;
  unit: string | null;
  user_id: string;
}

export interface ExportProgressEntry {
  goal_id: string;
  user_id: string;
  value: number;
  period_start: string;
  cadence: string;
  metric_type: string;
  target_value: number | null;
}

export interface ExportMember {
  id: string;
  display_name: string;
  email: string;
  goals: ExportGoal[];
}

/** Format year/month/day as "YYYY-MM-DD" without Date object timezone issues */
function formatDateString(year: number, month: number, day: number): string {
  return `${year}-${String(month + 1).padStart(2, '0')}-${String(day).padStart(2, '0')}`;
}

// --- Helpers ---

export function sanitizeSheetName(name: string): string {
  let sanitized = name.replace(/[\\/*?:[\]]/g, '');
  if (sanitized.length > 31) sanitized = sanitized.substring(0, 31);
  if (sanitized.length === 0) sanitized = 'Sheet';
  return sanitized;
}

export function sanitizeFilename(name: string): string {
  return name.replace(/[^a-zA-Z0-9_-]/g, '_').substring(0, 100);
}

function formatDate(dateString: string): string {
  const date = new Date(dateString);
  return date.toLocaleDateString('en-US', {
    month: 'short',
    day: 'numeric',
    year: 'numeric'
  });
}

function capitalizeFirst(str: string): string {
  return str.charAt(0).toUpperCase() + str.slice(1);
}

// --- Calculation helpers ---

export function calculateTotalPossible(
  cadence: 'daily' | 'weekly' | 'monthly' | 'yearly',
  startDate: string,
  endDate: string
): number {
  const start = new Date(startDate);
  const end = new Date(endDate);

  switch (cadence) {
    case 'daily': {
      const daysDiff = Math.floor((end.getTime() - start.getTime()) / (1000 * 60 * 60 * 24));
      return daysDiff + 1;
    }
    case 'weekly': {
      const weeksDiff = Math.ceil((end.getTime() - start.getTime()) / (1000 * 60 * 60 * 24 * 7));
      return weeksDiff;
    }
    case 'monthly': {
      let months = 0;
      const current = new Date(start);
      while (current <= end) {
        months++;
        current.setMonth(current.getMonth() + 1);
      }
      return months;
    }
    case 'yearly':
      return end.getFullYear() - start.getFullYear() + 1;
    default:
      return 0;
  }
}

export function calculateCompletedCount(
  goal: ExportGoal,
  progressData: ExportProgressEntry[]
): number {
  const goalProgress = progressData.filter(
    (p) => p.goal_id === goal.id && p.user_id === goal.user_id
  );

  switch (goal.metric_type) {
    case 'binary':
      return goalProgress.filter((p) => p.value === 1).length;
    case 'numeric':
    case 'duration':
      return goalProgress.filter((p) => p.value >= (goal.target_value ?? 0)).length;
    default:
      return 0;
  }
}

export function checkYearlyGoalProgress(
  goal: ExportGoal,
  progressData: ExportProgressEntry[],
  _startDate: string,
  _endDate: string
): boolean {
  const goalProgress = progressData.filter(
    (p) => p.goal_id === goal.id && p.user_id === goal.user_id && p.value > 0
  );
  return goalProgress.length > 0;
}

export function checkProgressForDate(
  goal: ExportGoal,
  progressData: ExportProgressEntry[],
  dateString: string
): boolean {
  const entry = progressData.find(
    (p) =>
      p.goal_id === goal.id &&
      p.user_id === goal.user_id &&
      p.period_start === dateString
  );
  if (!entry) return false;
  switch (goal.metric_type) {
    case 'binary':
      return entry.value === 1;
    case 'numeric':
    case 'duration':
      return entry.value >= (goal.target_value ?? 0);
    default:
      return false;
  }
}

export function checkWeeklyProgress(
  goal: ExportGoal,
  progressData: ExportProgressEntry[],
  weekStartDate: string
): boolean {
  const entry = progressData.find(
    (p) =>
      p.goal_id === goal.id &&
      p.user_id === goal.user_id &&
      p.period_start === weekStartDate
  );
  if (!entry) return false;
  switch (goal.metric_type) {
    case 'binary':
      return entry.value === 1;
    case 'numeric':
    case 'duration':
      return entry.value >= (goal.target_value ?? 0);
    default:
      return false;
  }
}

function getMonday(date: Date): Date {
  const d = new Date(date);
  const day = d.getDay();
  const diff = d.getDate() - day + (day === 0 ? -6 : 1);
  d.setDate(diff);
  return d;
}

function getWeekStartFromDateRow(
  dateRow: ExcelJS.Row,
  monthDate: Date
): string | null {
  for (let col = 1; col <= 7; col++) {
    const val = dateRow.getCell(col).value;
    if (val !== undefined && val !== null && typeof val === 'number') {
      const d = new Date(monthDate.getFullYear(), monthDate.getMonth(), val);
      const monday = getMonday(d);
      return formatDateString(monday.getFullYear(), monday.getMonth(), monday.getDate());
    }
  }
  return null;
}

export function calculateMonthlyStats(
  goal: ExportGoal,
  progressData: ExportProgressEntry[],
  monthDate: Date,
  overallStart: string,
  overallEnd: string
): { completed: number; total: number; percentage: string; percentageValue: number } {
  const monthStart = new Date(monthDate.getFullYear(), monthDate.getMonth(), 1);
  const monthEnd = new Date(monthDate.getFullYear(), monthDate.getMonth() + 1, 0);
  const effectiveStart = new Date(
    Math.max(monthStart.getTime(), new Date(overallStart).getTime())
  );
  const effectiveEnd = new Date(
    Math.min(monthEnd.getTime(), new Date(overallEnd).getTime())
  );

  if (goal.cadence === 'daily') {
    const daysInRange =
      Math.floor(
        (effectiveEnd.getTime() - effectiveStart.getTime()) / (1000 * 60 * 60 * 24)
      ) + 1;
    let completed = 0;
    for (let d = new Date(effectiveStart); d <= effectiveEnd; d.setDate(d.getDate() + 1)) {
      const dateString = formatDateString(d.getFullYear(), d.getMonth(), d.getDate());
      if (checkProgressForDate(goal, progressData, dateString)) completed++;
    }
    const percentageValue = (completed / daysInRange) * 100;
    return {
      completed,
      total: daysInRange,
      percentage: percentageValue.toFixed(1),
      percentageValue
    };
  }

  if (goal.cadence === 'weekly') {
    let weeksInRange = 0;
    let completed = 0;
    let currentWeekStart = getMonday(effectiveStart);
    const finalWeekStart = getMonday(effectiveEnd);

    while (currentWeekStart <= finalWeekStart) {
      weeksInRange++;
      const weekStartString = formatDateString(currentWeekStart.getFullYear(), currentWeekStart.getMonth(), currentWeekStart.getDate());
      if (checkWeeklyProgress(goal, progressData, weekStartString)) completed++;
      currentWeekStart.setDate(currentWeekStart.getDate() + 7);
    }

    const percentageValue =
      weeksInRange > 0 ? (completed / weeksInRange) * 100 : 0;
    return {
      completed,
      total: weeksInRange,
      percentage: percentageValue.toFixed(1),
      percentageValue
    };
  }

  return { completed: 0, total: 0, percentage: '0.0', percentageValue: 0 };
}

// --- Summary section ---

export function generateSummarySection(
  worksheet: Worksheet,
  startRow: number,
  member: ExportMember,
  groupName: string,
  goals: ExportGoal[],
  progressData: ExportProgressEntry[],
  startDate: string,
  endDate: string
): number {
  const memberProgress = progressData.filter((p) => p.user_id === member.id);
  const dailyWeeklyMonthly = goals.filter(
    (g) => g.cadence === 'daily' || g.cadence === 'weekly' || g.cadence === 'monthly'
  );
  const yearlyGoals = goals.filter((g) => g.cadence === 'yearly');

  let currentRow = startRow;

  // Title
  const titleRow = worksheet.getRow(currentRow);
  titleRow.height = 24;
  titleRow.getCell(1).value = `${member.display_name.toUpperCase()} - PROGRESS SUMMARY`;
  titleRow.getCell(1).font = {
    name: 'Segoe UI',
    size: 16,
    bold: true,
    color: { argb: 'FF1565C0' }
  };
  titleRow.getCell(1).alignment = { vertical: 'middle', horizontal: 'left' };
  currentRow++;

  // Subtitle
  const subtitleRow = worksheet.getRow(currentRow);
  subtitleRow.height = 18;
  subtitleRow.getCell(1).value = `${groupName} | ${formatDate(startDate)} - ${formatDate(endDate)}`;
  subtitleRow.getCell(1).font = {
    name: 'Segoe UI',
    size: 11,
    color: { argb: 'FF616161' }
  };
  subtitleRow.getCell(1).alignment = { vertical: 'middle', horizontal: 'left' };
  currentRow += 2;

  // Header row
  const headerRow = worksheet.getRow(currentRow);
  headerRow.height = 22;
  headerRow.getCell(1).value = 'Goal';
  headerRow.getCell(2).value = 'Cadence';
  headerRow.getCell(3).value = 'Completion';
  headerRow.getCell(4).value = 'Days/Times';
  headerRow.font = {
    name: 'Segoe UI',
    size: 11,
    bold: true,
    color: { argb: 'FFFFFFFF' }
  };
  headerRow.fill = {
    type: 'pattern',
    pattern: 'solid',
    fgColor: { argb: 'FF1565C0' }
  };
  headerRow.alignment = { vertical: 'middle', horizontal: 'center' };
  headerRow.border = {
    bottom: { style: 'medium', color: { argb: 'FF1565C0' } }
  };
  currentRow++;

  worksheet.getColumn(1).width = 30;
  worksheet.getColumn(2).width = 12;
  worksheet.getColumn(3).width = 14;
  worksheet.getColumn(4).width = 14;

  const dataStartRow = currentRow;
  for (const goal of dailyWeeklyMonthly) {
    const row = worksheet.getRow(currentRow);
    row.height = 20;

    const completedCount = calculateCompletedCount(goal, memberProgress);
    const totalPossible = calculateTotalPossible(goal.cadence, startDate, endDate);
    const percentage =
      totalPossible > 0
        ? ((completedCount / totalPossible) * 100).toFixed(1)
        : '0.0';

    row.getCell(1).value = goal.title;
    row.getCell(2).value = capitalizeFirst(goal.cadence);
    row.getCell(3).value = `${percentage}%`;
    row.getCell(4).value = `${completedCount}/${totalPossible}`;

    row.font = { name: 'Segoe UI', size: 10 };
    row.getCell(1).alignment = { vertical: 'middle', horizontal: 'left' };
    row.getCell(2).alignment = { vertical: 'middle', horizontal: 'center' };
    row.getCell(3).alignment = { vertical: 'middle', horizontal: 'center' };
    row.getCell(4).alignment = { vertical: 'middle', horizontal: 'center' };

    const completionCell = row.getCell(3);
    const percentValue = parseFloat(percentage);
    if (percentValue >= 80) {
      completionCell.font = {
        ...(completionCell.font as object),
        name: 'Segoe UI',
        size: 10,
        color: { argb: 'FF2E7D32' },
        bold: true
      };
    } else if (percentValue >= 60) {
      completionCell.font = {
        ...(completionCell.font as object),
        name: 'Segoe UI',
        size: 10,
        color: { argb: 'FFF57C00' },
        bold: true
      };
    } else {
      completionCell.font = {
        ...(completionCell.font as object),
        name: 'Segoe UI',
        size: 10,
        color: { argb: 'FFC62828' },
        bold: true
      };
    }

    if ((currentRow - dataStartRow) % 2 === 0) {
      row.fill = {
        type: 'pattern',
        pattern: 'solid',
        fgColor: { argb: 'FFF5F5F5' }
      };
    }
    row.eachCell((cell) => {
      cell.border = {
        top: { style: 'thin', color: { argb: 'FFE0E0E0' } },
        bottom: { style: 'thin', color: { argb: 'FFE0E0E0' } }
      };
    });
    currentRow++;
  }

  currentRow++;
  const yearlyStartRow = currentRow;

  // Yearly goals header
  const yearlyHeaderRow = worksheet.getRow(currentRow);
  yearlyHeaderRow.height = 22;
  yearlyHeaderRow.getCell(1).value = 'YEARLY GOALS';
  yearlyHeaderRow.getCell(1).font = {
    name: 'Segoe UI',
    size: 12,
    bold: true,
    color: { argb: 'FF1565C0' }
  };
  yearlyHeaderRow.getCell(1).alignment = { vertical: 'middle', horizontal: 'left' };
  currentRow++;

  for (const goal of yearlyGoals) {
    const row = worksheet.getRow(currentRow);
    row.height = 20;
    const hasProgress = checkYearlyGoalProgress(
      goal,
      memberProgress,
      startDate,
      endDate
    );

    row.getCell(1).value = goal.title;
    row.getCell(2).value = 'Yearly';
    row.getCell(3).value = hasProgress ? 'Complete' : 'Pending';
    row.getCell(4).value = hasProgress ? '✓' : '-';

    row.font = { name: 'Segoe UI', size: 10 };
    row.getCell(1).alignment = { vertical: 'middle', horizontal: 'left' };
    row.getCell(2).alignment = { vertical: 'middle', horizontal: 'center' };
    row.getCell(3).alignment = { vertical: 'middle', horizontal: 'center' };
    row.getCell(4).alignment = { vertical: 'middle', horizontal: 'center' };

    const statusCell = row.getCell(3);
    if (hasProgress) {
      statusCell.font = {
        ...(statusCell.font as object),
        name: 'Segoe UI',
        size: 10,
        color: { argb: 'FF2E7D32' },
        bold: true
      };
    } else {
      statusCell.font = {
        ...(statusCell.font as object),
        name: 'Segoe UI',
        size: 10,
        color: { argb: 'FF757575' }
      };
    }

    if ((currentRow - yearlyStartRow) % 2 === 0) {
      row.fill = {
        type: 'pattern',
        pattern: 'solid',
        fgColor: { argb: 'FFF5F5F5' }
      };
    }
    currentRow++;
  }

  return currentRow;
}

// --- Calendar section ---

function generateMonthCalendar(
  worksheet: Worksheet,
  startRow: number,
  goals: ExportGoal[],
  progressData: ExportProgressEntry[],
  monthDate: Date,
  overallStart: string,
  overallEnd: string,
  _userTimezone: string
): number {
  let currentRow = startRow;
  const calendarGoals = goals.filter(
    (g) => g.cadence === 'daily' || g.cadence === 'weekly'
  );

  if (calendarGoals.length === 0) return currentRow;

  const monthStart = new Date(monthDate.getFullYear(), monthDate.getMonth(), 1);
  const monthEnd = new Date(
    monthDate.getFullYear(),
    monthDate.getMonth() + 1,
    0
  );
  const firstDayOfWeek = monthStart.getDay();
  const daysInMonth = monthEnd.getDate();

  // Month header
  const monthHeaderRow = worksheet.getRow(currentRow);
  monthHeaderRow.height = 24;
  worksheet.mergeCells(currentRow, 1, currentRow, 9);
  monthHeaderRow.getCell(1).value = monthDate
    .toLocaleDateString('en-US', { month: 'long', year: 'numeric' })
    .toUpperCase();
  monthHeaderRow.getCell(1).font = {
    name: 'Segoe UI',
    size: 14,
    bold: true,
    color: { argb: 'FF1565C0' }
  };
  monthHeaderRow.getCell(1).alignment = { vertical: 'middle', horizontal: 'left' };
  monthHeaderRow.getCell(1).fill = {
    type: 'pattern',
    pattern: 'solid',
    fgColor: { argb: 'FFE3F2FD' }
  };
  currentRow++;

  // Day headers
  const dayHeaderRow = worksheet.getRow(currentRow);
  dayHeaderRow.height = 20;
  dayHeaderRow.getCell(1).value = 'Sun';
  dayHeaderRow.getCell(2).value = 'Mon';
  dayHeaderRow.getCell(3).value = 'Tue';
  dayHeaderRow.getCell(4).value = 'Wed';
  dayHeaderRow.getCell(5).value = 'Thu';
  dayHeaderRow.getCell(6).value = 'Fri';
  dayHeaderRow.getCell(7).value = 'Sat';
  dayHeaderRow.getCell(8).value = '';
  dayHeaderRow.getCell(9).value = 'Weekly Goal';
  dayHeaderRow.font = {
    name: 'Segoe UI',
    size: 10,
    bold: true,
    color: { argb: 'FFFFFFFF' }
  };
  dayHeaderRow.fill = {
    type: 'pattern',
    pattern: 'solid',
    fgColor: { argb: 'FF1565C0' }
  };
  dayHeaderRow.alignment = { vertical: 'middle', horizontal: 'center' };
  for (let col = 1; col <= 7; col++) {
    const colObj = worksheet.getColumn(col);
    if ((colObj.width ?? 0) < 6) colObj.width = 6;
  }
  const col8 = worksheet.getColumn(8);
  if ((col8.width ?? 0) < 2) col8.width = 2;
  const col9 = worksheet.getColumn(9);
  if ((col9.width ?? 0) < 24) col9.width = 24;
  currentRow++;

  let dayOfMonth = 1;
  let weekStartRow = currentRow;

  while (dayOfMonth <= daysInMonth) {
    const dateRow = worksheet.getRow(currentRow);
    dateRow.height = 18;
    const dateValues: (number | string)[] = ['', '', '', '', '', '', '', '', ''];

    for (let dayOfWeek = 0; dayOfWeek < 7 && dayOfMonth <= daysInMonth; dayOfWeek++) {
      if (currentRow === weekStartRow && dayOfWeek < firstDayOfWeek) {
        dateValues[dayOfWeek] = '';
      } else if (dayOfMonth <= daysInMonth) {
        dateValues[dayOfWeek] = dayOfMonth;
        dayOfMonth++;
      }
    }

    for (let c = 1; c <= 9; c++) {
      dateRow.getCell(c).value = dateValues[c - 1];
    }
    dateRow.font = { name: 'Segoe UI', size: 9, bold: true };
    dateRow.alignment = { vertical: 'middle', horizontal: 'center' };
    dateRow.fill = {
      type: 'pattern',
      pattern: 'solid',
      fgColor: { argb: 'FFFAFAFA' }
    };
    for (let col = 1; col <= 7; col++) {
      dateRow.getCell(col).border = {
        top: { style: 'thin', color: { argb: 'FFBDBDBD' } },
        left: { style: 'thin', color: { argb: 'FFBDBDBD' } },
        right: { style: 'thin', color: { argb: 'FFBDBDBD' } },
        bottom: { style: 'thin', color: { argb: 'FFBDBDBD' } }
      };
    }
    currentRow++;

    for (let goalIdx = 0; goalIdx < calendarGoals.length; goalIdx++) {
      const goal = calendarGoals[goalIdx];
      const goalRow = worksheet.getRow(currentRow);
      goalRow.height = 18;

      const goalValues: (string | number)[] = [
        '',
        '',
        '',
        '',
        '',
        '',
        '',
        '',
        ''
      ];

      for (let col = 1; col <= 7; col++) {
        const dateValue = dateRow.getCell(col).value;
        if (dateValue !== undefined && dateValue !== null && typeof dateValue === 'number') {
          const dateString = formatDateString(monthDate.getFullYear(), monthDate.getMonth(), dateValue);
          if (dateString >= overallStart && dateString <= overallEnd) {
            goalValues[col - 1] = checkProgressForDate(goal, progressData, dateString)
              ? '✓'
              : '-';
          }
        }
      }

      const weekStart = getWeekStartFromDateRow(dateRow, monthDate);
      if (goal.cadence === 'weekly' && weekStart) {
        goalValues[8] = checkWeeklyProgress(goal, progressData, weekStart) ? '✓' : '-';
      } else {
        goalValues[8] = goal.title;
      }

      for (let c = 1; c <= 9; c++) {
        goalRow.getCell(c).value = goalValues[c - 1];
      }
      goalRow.font = { name: 'Segoe UI', size: 9 };
      goalRow.alignment = { vertical: 'middle', horizontal: 'center' };

      for (let col = 1; col <= 9; col++) {
        const cell = goalRow.getCell(col);
        const val = cell.value;
        if (val === '✓') {
          cell.font = {
            name: 'Segoe UI',
            size: 9,
            color: { argb: 'FF2E7D32' },
            bold: true
          };
        } else if (val === '-') {
          cell.font = {
            name: 'Segoe UI',
            size: 9,
            color: { argb: 'FFBDBDBD' }
          };
        }
        if (col <= 7) {
          cell.border = {
            left: { style: 'thin', color: { argb: 'FFE0E0E0' } },
            right: { style: 'thin', color: { argb: 'FFE0E0E0' } },
            bottom: { style: 'thin', color: { argb: 'FFE0E0E0' } }
          };
        }
      }
      if (goal.cadence === 'weekly') {
        goalRow.getCell(9).value = goal.title;
        goalRow.getCell(9).alignment = { vertical: 'middle', horizontal: 'left' };
        goalRow.getCell(9).font = {
          name: 'Segoe UI',
          size: 9,
          italic: true
        };
      }
      currentRow++;
    }
  }

  // Month summary
  currentRow++;
  const summaryHeaderRow = worksheet.getRow(currentRow);
  summaryHeaderRow.height = 20;
  worksheet.mergeCells(currentRow, 1, currentRow, 9);
  summaryHeaderRow.getCell(1).value = `${monthDate
    .toLocaleDateString('en-US', { month: 'long' })
    .toUpperCase()} SUMMARY`;
  summaryHeaderRow.getCell(1).font = {
    name: 'Segoe UI',
    size: 11,
    bold: true,
    color: { argb: 'FF1565C0' }
  };
  summaryHeaderRow.getCell(1).alignment = { vertical: 'middle', horizontal: 'left' };
  summaryHeaderRow.getCell(1).fill = {
    type: 'pattern',
    pattern: 'solid',
    fgColor: { argb: 'FFF5F5F5' }
  };
  currentRow++;

  for (const goal of calendarGoals) {
    const summaryRow = worksheet.getRow(currentRow);
    summaryRow.height = 18;
    worksheet.mergeCells(currentRow, 1, currentRow, 9);
    const monthlyStats = calculateMonthlyStats(
      goal,
      progressData,
      monthDate,
      overallStart,
      overallEnd
    );
    const summaryCell = summaryRow.getCell(1);
    summaryCell.value = `${goal.title}: ${monthlyStats.completed}/${monthlyStats.total} ${
      goal.cadence === 'weekly' ? 'weeks' : 'days'
    } (${monthlyStats.percentage}%)`;
    summaryCell.font = { name: 'Segoe UI', size: 10 };
    summaryCell.alignment = {
      vertical: 'middle',
      horizontal: 'left',
      indent: 1
    };
    if (monthlyStats.percentageValue >= 80) {
      summaryCell.font = {
        name: 'Segoe UI',
        size: 10,
        color: { argb: 'FF2E7D32' }
      };
    } else if (monthlyStats.percentageValue >= 60) {
      summaryCell.font = {
        name: 'Segoe UI',
        size: 10,
        color: { argb: 'FFF57C00' }
      };
    } else {
      summaryCell.font = {
        name: 'Segoe UI',
        size: 10,
        color: { argb: 'FFC62828' }
      };
    }
    currentRow++;
  }

  return currentRow;
}

export function generateCalendarSection(
  worksheet: Worksheet,
  startRow: number,
  goals: ExportGoal[],
  progressData: ExportProgressEntry[],
  startDate: string,
  endDate: string,
  userTimezone: string
): void {
  const start = new Date(startDate);
  const end = new Date(endDate);
  let currentRow = startRow;
  let currentMonth = new Date(start.getFullYear(), start.getMonth(), 1);

  while (currentMonth <= end) {
    currentRow = generateMonthCalendar(
      worksheet,
      currentRow,
      goals,
      progressData,
      new Date(currentMonth),
      startDate,
      endDate,
      userTimezone
    );
    currentMonth.setMonth(currentMonth.getMonth() + 1);
    currentRow += 2;
  }
}
