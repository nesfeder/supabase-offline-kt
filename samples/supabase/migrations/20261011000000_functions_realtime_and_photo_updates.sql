-- Offline Tasks: the second migration, after 20260930000000_offline_tasks.sql.
--
-- Two database functions the samples call through `rpc`, a Storage policy that lets a user
-- replace and move their own photo files, and the two tables added to Realtime's publication so
-- the samples can apply other devices' changes as they happen.
--
-- Run it after the first file: `supabase db push` from samples/ applies both in order, or paste
-- each file into the SQL Editor and run it, the first one first. Like the first, it can be run
-- again and changes nothing. Step by step:
-- https://nesfeder.github.io/supabase-offline-kt/samples/supabase-setup/

-- =============================================================================================
-- Functions
-- =============================================================================================

-- Marks every open task of the caller done, and returns how many it changed. It runs as the
-- caller (security invoker), so the tasks policies decide which rows it reaches: only their own.
create or replace function public.complete_all_tasks()
returns integer
language sql
security invoker
set search_path = ''
as $$
  with changed as (
    update public.tasks
    set done = true
    where user_id = (select auth.uid()) and not done
    returning 1
  )
  select count(*)::integer from changed;
$$;

-- How many of the caller's tasks are done; with `created_since`, only those created then or
-- later. Called with no arguments, it counts them all.
create or replace function public.tasks_done_count(created_since timestamptz default null)
returns integer
language sql
stable
security invoker
set search_path = ''
as $$
  select count(*)::integer
  from public.tasks
  where user_id = (select auth.uid())
    and done
    and (created_since is null or created_at >= created_since);
$$;

-- Signed-in users only, as for the tables. Postgres grants execute to everyone by default.
revoke all on function public.complete_all_tasks() from public, anon;
revoke all on function public.tasks_done_count(timestamptz) from public, anon;
grant execute on function public.complete_all_tasks() to authenticated, service_role;
grant execute on function public.tasks_done_count(timestamptz) to authenticated, service_role;

-- =============================================================================================
-- Storage: replacing and moving a photo file
-- =============================================================================================

-- Storage's update (replacing a file in place) and move (renaming it) both need an update policy;
-- copy needs only the first file's read and upload policies. Each only under the user's own
-- folder, before and after.
drop policy if exists "task-photos: owner updates" on storage.objects;
create policy "task-photos: owner updates" on storage.objects
  for update to authenticated
  using (
    bucket_id = 'task-photos'
    and (storage.foldername(name))[1] = (select auth.uid()::text)
  )
  with check (
    bucket_id = 'task-photos'
    and (storage.foldername(name))[1] = (select auth.uid()::text)
  );

-- =============================================================================================
-- Realtime: the two tables in the supabase_realtime publication
-- =============================================================================================

-- Realtime sends a table's changes only when the table is in this publication, and only to the
-- users whose policies admit the row. Each table is added once, so a second run changes nothing.
do $$
declare
  table_name text;
begin
  if not exists (select 1 from pg_catalog.pg_publication where pubname = 'supabase_realtime') then
    raise exception 'there is no supabase_realtime publication'
      using hint = 'Hosted Supabase projects have one; turn Realtime on for the project first.';
  end if;
  foreach table_name in array array['tasks', 'task_photos'] loop
    if not exists (
      select 1
      from pg_catalog.pg_publication_tables t
      where t.pubname = 'supabase_realtime'
        and t.schemaname = 'public'
        and t.tablename = table_name
    ) then
      execute format('alter publication supabase_realtime add table public.%I', table_name);
    end if;
  end loop;
end
$$;

-- =============================================================================================
-- how to reset
-- =============================================================================================
--
-- Removes everything this file created. Run it before the first file's reset. Uncomment and run:
--
-- alter publication supabase_realtime drop table public.tasks, public.task_photos;
-- drop policy if exists "task-photos: owner updates" on storage.objects;
-- drop function if exists public.tasks_done_count(timestamptz);
-- drop function if exists public.complete_all_tasks();
