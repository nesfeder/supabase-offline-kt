-- Schema for the Supabase Offline SDK demo TODO app.
--
-- Run this file once against a fresh Supabase project (Supabase Dashboard -> SQL Editor -> New
-- query -> paste -> Run). It is safe to re-run -- everything uses IF NOT EXISTS / ON CONFLICT.
--
-- After running this you can launch either the Android (`composeApp/`) or iOS (`iosApp/`) demo
-- against the same project using only its anon key. The RLS policies below are intentionally
-- permissive (anon can read/write everything) because the demo runs unauthenticated. DO NOT
-- copy these policies into a production project.

create extension if not exists "moddatetime";

-- =============================================================================
-- tasks
-- =============================================================================

create table if not exists public.tasks (
    id          uuid        primary key default gen_random_uuid(),
    title       text        not null,
    notes       text,
    is_done     boolean     not null default false,
    created_at  timestamptz not null default now(),
    updated_at  timestamptz not null default now()
);

create index if not exists tasks_created_at_idx
    on public.tasks (created_at desc);

drop trigger if exists tasks_set_updated_at on public.tasks;
create trigger tasks_set_updated_at
    before update on public.tasks
    for each row
    execute function moddatetime(updated_at);

alter table public.tasks enable row level security;

drop policy if exists "demo_tasks_anon_select" on public.tasks;
create policy "demo_tasks_anon_select"
    on public.tasks for select
    to anon
    using (true);

drop policy if exists "demo_tasks_anon_insert" on public.tasks;
create policy "demo_tasks_anon_insert"
    on public.tasks for insert
    to anon
    with check (true);

drop policy if exists "demo_tasks_anon_update" on public.tasks;
create policy "demo_tasks_anon_update"
    on public.tasks for update
    to anon
    using (true)
    with check (true);

drop policy if exists "demo_tasks_anon_delete" on public.tasks;
create policy "demo_tasks_anon_delete"
    on public.tasks for delete
    to anon
    using (true);

-- =============================================================================
-- task_photos
-- =============================================================================

create table if not exists public.task_photos (
    id            uuid        primary key default gen_random_uuid(),
    task_id       uuid        not null references public.tasks(id) on delete cascade,
    bucket_id     text        not null default 'task-photos',
    object_path   text        not null,
    content_type  text,
    size_bytes    bigint,
    uploaded_at   timestamptz not null default now()
);

create index if not exists task_photos_task_id_idx
    on public.task_photos (task_id);

create index if not exists task_photos_uploaded_at_idx
    on public.task_photos (uploaded_at desc);

alter table public.task_photos enable row level security;

drop policy if exists "demo_task_photos_anon_select" on public.task_photos;
create policy "demo_task_photos_anon_select"
    on public.task_photos for select
    to anon
    using (true);

drop policy if exists "demo_task_photos_anon_insert" on public.task_photos;
create policy "demo_task_photos_anon_insert"
    on public.task_photos for insert
    to anon
    with check (true);

drop policy if exists "demo_task_photos_anon_update" on public.task_photos;
create policy "demo_task_photos_anon_update"
    on public.task_photos for update
    to anon
    using (true)
    with check (true);

drop policy if exists "demo_task_photos_anon_delete" on public.task_photos;
create policy "demo_task_photos_anon_delete"
    on public.task_photos for delete
    to anon
    using (true);

-- =============================================================================
-- Storage bucket: task-photos
-- =============================================================================

insert into storage.buckets (id, name, public)
values ('task-photos', 'task-photos', true)
on conflict (id) do nothing;

drop policy if exists "demo_storage_anon_select" on storage.objects;
create policy "demo_storage_anon_select"
    on storage.objects for select
    to anon
    using (bucket_id = 'task-photos');

drop policy if exists "demo_storage_anon_insert" on storage.objects;
create policy "demo_storage_anon_insert"
    on storage.objects for insert
    to anon
    with check (bucket_id = 'task-photos');

drop policy if exists "demo_storage_anon_update" on storage.objects;
create policy "demo_storage_anon_update"
    on storage.objects for update
    to anon
    using (bucket_id = 'task-photos')
    with check (bucket_id = 'task-photos');

drop policy if exists "demo_storage_anon_delete" on storage.objects;
create policy "demo_storage_anon_delete"
    on storage.objects for delete
    to anon
    using (bucket_id = 'task-photos');
