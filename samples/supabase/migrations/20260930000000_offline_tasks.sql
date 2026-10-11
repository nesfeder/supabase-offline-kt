-- Offline Tasks: the Supabase schema both sample apps run against.
--
-- Two tables and one Storage bucket. A signed-in user reads and writes only the rows and files
-- that carry their own id; a request with no user behind it reaches nothing. The samples sign
-- in anonymously, so every install is a user of its own.
--
-- Before you run it, turn on anonymous sign-ins: Authentication > Sign In / Providers >
-- "Allow anonymous sign-ins". Then paste this whole file into the SQL Editor and run it, or run
-- `supabase db push` from samples/, where this file is the first of the migrations. Step by step:
-- https://nesfeder.github.io/supabase-offline-kt/samples/supabase-setup/
--
-- It can be run again. A second run changes nothing, and it puts back any policy, grant,
-- trigger or bucket setting edited since. The SQL Editor warns that the query "includes
-- destructive operations": the file drops and recreates its own policies, and nothing else.
-- To remove it all, see "how to reset" at the end.

-- Stop, with a clear message and before any change, on three things this file would otherwise
-- keep: a table of the same name in another shape (`create table if not exists` keeps it), the
-- moddatetime extension in another schema, and other policies on the task-photos bucket.
do $$
declare
  missing_columns text;
  extension_schema text;
  other_policies text;
begin
  select string_agg(format('public.%I (no %s)', expected.table_name, expected.column_name), ', ')
  into missing_columns
  from (
    values
      ('tasks', 'id'), ('tasks', 'user_id'), ('tasks', 'title'), ('tasks', 'notes'),
      ('tasks', 'done'), ('tasks', 'created_at'), ('tasks', 'updated_at'),
      ('task_photos', 'id'), ('task_photos', 'user_id'), ('task_photos', 'task_id'),
      ('task_photos', 'object_path'), ('task_photos', 'width'), ('task_photos', 'height'),
      ('task_photos', 'created_at'), ('task_photos', 'updated_at')
  ) as expected (table_name, column_name)
  join pg_catalog.pg_class c
    on c.relnamespace = 'public'::regnamespace and c.relname = expected.table_name
  where not exists (
    select 1
    from pg_catalog.pg_attribute a
    where a.attrelid = c.oid and a.attname = expected.column_name and not a.attisdropped
  );
  if missing_columns is not null then
    raise exception 'this file creates public.tasks and public.task_photos, but found a table '
      'of that name in another shape: %', missing_columns
      using hint = 'Rename or drop it, or apply this file to a project of its own.';
  end if;

  select e.extnamespace::regnamespace::text
  into extension_schema
  from pg_catalog.pg_extension e
  where e.extname = 'moddatetime';
  if extension_schema is not null and extension_schema <> 'extensions' then
    raise exception 'moddatetime is installed in schema %, and this file uses it from extensions',
      extension_schema
      using hint = 'Move it: alter extension moddatetime set schema extensions;';
  end if;

  -- Policies are combined with OR, so another policy that names the bucket would widen who
  -- reaches it, whatever this file's own policies say.
  select string_agg(format('%I', p.policyname), ', ')
  into other_policies
  from pg_catalog.pg_policies p
  where p.schemaname = 'storage'
    and p.tablename = 'objects'
    and p.policyname not in (
      'task-photos: owner reads', 'task-photos: owner uploads', 'task-photos: owner deletes',
      -- The later migration's own policy, so this file still runs again after it.
      'task-photos: owner updates'
    )
    and concat(p.qual, ' ', p.with_check) like '%task-photos%';
  if other_policies is not null then
    raise exception 'storage.objects already has other policies for the task-photos bucket: %',
      other_policies
      using hint = 'They would apply beside this file''s. Drop them first; the old demo schema''s '
        'are named demo_storage_anon_*.';
  end if;
end
$$;

-- moddatetime keeps updated_at current. It goes in the extensions schema, not public, so the
-- Data API does not expose it (the dashboard's Security Advisor flags extensions in public).
create extension if not exists moddatetime with schema extensions;

-- =============================================================================================
-- tasks
-- =============================================================================================

create table if not exists public.tasks (
  -- Made by the app, never by the server, so this has no default. A task created offline must
  -- be addressable (opened, edited, given photos) before it syncs, and a replayed insert must
  -- carry the same id every time. An insert without one fails (not null) rather than getting
  -- an id the app never sees.
  id uuid primary key,
  -- The owner. The app sends it on insert so its cached copy is complete before it syncs; the
  -- default covers a client that leaves it out. The policies below require the caller's id
  -- either way. Deleting the user deletes their tasks.
  user_id uuid not null default auth.uid() references auth.users (id) on delete cascade,
  -- The one rule the samples use to show a rejected write. A write queued offline with a blank
  -- title (spaces, tabs or line breaks only), or one over 200 characters, is refused when it
  -- replays: SQLSTATE 23514, which the Data API returns as HTTP 400.
  title text not null
    constraint tasks_title_length
    check (btrim(title, E' \t\r\n') <> '' and char_length(title) <= 200),
  notes text constraint tasks_notes_length check (char_length(notes) <= 2000),
  done boolean not null default false,
  -- The app sends created_at on insert, so a task made offline sorts correctly before it syncs.
  created_at timestamptz not null default now(),
  -- The server sets updated_at on every update (the trigger below); a value the app sends in an
  -- update is overwritten.
  updated_at timestamptz not null default now(),
  -- Lets task_photos require that a photo and its task have the same owner.
  constraint tasks_id_user_id_key unique (id, user_id)
);

-- The task list: the caller's tasks, newest first.
create index if not exists tasks_user_id_created_at_idx
  on public.tasks (user_id, created_at desc);

create or replace trigger tasks_set_updated_at
  before update on public.tasks
  for each row execute function extensions.moddatetime(updated_at);

-- Forced as well as enabled, so the policies bind the table's owner too, unless that role
-- bypasses row-level security. Supabase's postgres role does, so the dashboard still sees and
-- edits every row.
alter table public.tasks enable row level security;
alter table public.tasks force row level security;

-- Which roles reach the table at all. A project that automatically exposes new tables grants
-- every privilege to anon, authenticated and service_role, TRUNCATE included, which row-level
-- security does not cover; a newer project grants nothing. Both end here: nothing for anon (a
-- request with only the publishable key), the four row operations for signed-in users
-- (anonymous ones included) and for service_role. The samples never use the secret key;
-- service_role is granted for your own server-side code, such as a clean-up of orphaned files.
revoke all on table public.tasks from anon, authenticated, service_role;
grant select, insert, update, delete on table public.tasks to authenticated, service_role;

-- Owner-only, for every operation. `(select auth.uid())` is evaluated once per statement rather
-- than once per row, as Supabase recommends.
drop policy if exists "tasks: owner reads" on public.tasks;
create policy "tasks: owner reads" on public.tasks
  for select to authenticated
  using ((select auth.uid()) = user_id);

drop policy if exists "tasks: owner inserts" on public.tasks;
create policy "tasks: owner inserts" on public.tasks
  for insert to authenticated
  with check ((select auth.uid()) = user_id);

drop policy if exists "tasks: owner updates" on public.tasks;
create policy "tasks: owner updates" on public.tasks
  for update to authenticated
  using ((select auth.uid()) = user_id)
  with check ((select auth.uid()) = user_id);

drop policy if exists "tasks: owner deletes" on public.tasks;
create policy "tasks: owner deletes" on public.tasks
  for delete to authenticated
  using ((select auth.uid()) = user_id);

-- =============================================================================================
-- task_photos
-- =============================================================================================

create table if not exists public.task_photos (
  -- Made by the app, like tasks.id, and part of the photo's object path.
  id uuid primary key,
  user_id uuid not null default auth.uid() references auth.users (id) on delete cascade,
  task_id uuid not null,
  -- Where the image is in the task-photos bucket: exactly "<user_id>/<task_id>/<id>.jpg", with
  -- the ids in lower case, as Postgres writes a uuid. A fresh path per photo, never reused.
  object_path text not null,
  -- The image's size in pixels, so the app can reserve its space before it loads. Null when
  -- unknown, never 0.
  width integer constraint task_photos_width_positive check (width > 0),
  height integer constraint task_photos_height_positive check (height > 0),
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now(),
  -- The Storage policies admit a file only under its owner's folder; the row can name only the
  -- one file its own ids spell. A path in upper case is refused here and by Storage alike.
  constraint task_photos_object_path_layout
    check (object_path = user_id::text || '/' || task_id::text || '/' || id::text || '.jpg'),
  -- A photo belongs to a task with the same owner. The pair must exist in tasks, so no one can
  -- attach a photo to another user's task, even knowing its id. Deleting a task deletes its
  -- photo rows here, but not its files: Storage is a separate service, and only the app can
  -- delete them. An offline cache does not see the cascade either, so the samples delete a
  -- task's files (when online), then its photo rows, then the task.
  constraint task_photos_task_fkey foreign key (task_id, user_id)
    references public.tasks (id, user_id) on delete cascade
);

-- One task's photos, and the lookup the cascade makes when a task is deleted.
create index if not exists task_photos_task_id_user_id_idx
  on public.task_photos (task_id, user_id);

-- The Photos screen: the caller's photos, newest first.
create index if not exists task_photos_user_id_created_at_idx
  on public.task_photos (user_id, created_at desc);

create or replace trigger task_photos_set_updated_at
  before update on public.task_photos
  for each row execute function extensions.moddatetime(updated_at);

alter table public.task_photos enable row level security;
alter table public.task_photos force row level security;

revoke all on table public.task_photos from anon, authenticated, service_role;
grant select, insert, update, delete on table public.task_photos to authenticated, service_role;

drop policy if exists "task_photos: owner reads" on public.task_photos;
create policy "task_photos: owner reads" on public.task_photos
  for select to authenticated
  using ((select auth.uid()) = user_id);

drop policy if exists "task_photos: owner inserts" on public.task_photos;
create policy "task_photos: owner inserts" on public.task_photos
  for insert to authenticated
  with check ((select auth.uid()) = user_id);

drop policy if exists "task_photos: owner updates" on public.task_photos;
create policy "task_photos: owner updates" on public.task_photos
  for update to authenticated
  using ((select auth.uid()) = user_id)
  with check ((select auth.uid()) = user_id);

drop policy if exists "task_photos: owner deletes" on public.task_photos;
create policy "task_photos: owner deletes" on public.task_photos
  for delete to authenticated
  using ((select auth.uid()) = user_id);

-- =============================================================================================
-- Storage: the task-photos bucket
-- =============================================================================================

-- Private: no file in it has a public URL, and every download passes the policies below.
-- Storage refuses an upload over 5 MB, or one not sent as image/jpeg: the samples re-encode
-- every photo to a JPEG that fits, to match the ".jpg" in its path.
insert into storage.buckets (id, name, public, file_size_limit, allowed_mime_types)
values (
  'task-photos',
  'task-photos',
  false,
  5242880,
  array['image/jpeg']
)
on conflict (id) do update set
  public = excluded.public,
  file_size_limit = excluded.file_size_limit,
  allowed_mime_types = excluded.allowed_mime_types;

-- A file lives under "<user_id>/...", and only its owner may read, upload or delete there: the
-- first folder of the path must be the caller's id, in lower case. This file adds no update
-- policy, so on its own a file is never replaced or moved, and an upload to a path already taken
-- is refused, with or without upsert. The second migration,
-- 20261011000000_functions_realtime_and_photo_updates.sql, adds the owner's update policy, which
-- Storage's replace and move need.
drop policy if exists "task-photos: owner reads" on storage.objects;
create policy "task-photos: owner reads" on storage.objects
  for select to authenticated
  using (
    bucket_id = 'task-photos'
    and (storage.foldername(name))[1] = (select auth.uid()::text)
  );

drop policy if exists "task-photos: owner uploads" on storage.objects;
create policy "task-photos: owner uploads" on storage.objects
  for insert to authenticated
  with check (
    bucket_id = 'task-photos'
    and (storage.foldername(name))[1] = (select auth.uid()::text)
  );

drop policy if exists "task-photos: owner deletes" on storage.objects;
create policy "task-photos: owner deletes" on storage.objects
  for delete to authenticated
  using (
    bucket_id = 'task-photos'
    and (storage.foldername(name))[1] = (select auth.uid()::text)
  );

-- =============================================================================================
-- how to reset
-- =============================================================================================
--
-- Removes everything this file created. SQL cannot delete Storage rows (Supabase blocks it, so
-- no stored file is left without its row), so first delete the bucket in the dashboard:
-- Storage > Files > task-photos > Delete bucket. That deletes its files too. Then uncomment and
-- run:
--
-- drop policy if exists "task-photos: owner reads" on storage.objects;
-- drop policy if exists "task-photos: owner uploads" on storage.objects;
-- drop policy if exists "task-photos: owner deletes" on storage.objects;
-- drop table if exists public.task_photos;
-- drop table if exists public.tasks;
--
-- The moddatetime extension stays, in case anything else uses it. So do the anonymous users,
-- under Authentication > Users; delete them there, or with the line below. Supabase refuses to
-- delete a user who still owns files, which is why the bucket goes first.
--
-- delete from auth.users where is_anonymous;
