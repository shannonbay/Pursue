-- Fix: Use invoker's permissions so RLS applies correctly
ALTER VIEW active_group_members SET (security_invoker = true);
ALTER VIEW pending_group_members SET (security_invoker = true);