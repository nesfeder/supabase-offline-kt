# Set up Supabase

Creating a Supabase project for the sample apps and loading their schema into it. A free project
is enough.

The schema is two files, applied in order, in
[`samples/supabase/migrations/`](#the-schema). The first,
`20260930000000_offline_tasks.sql`, creates two tables, `tasks` and `task_photos`, and a private
Storage bucket, `task-photos`. The second, `20261011000000_functions_realtime_and_photo_updates.sql`,
adds two database functions, a Storage policy that lets a user replace and move their own photo
files, and the two tables to Realtime's publication. Every row and every file belongs to one user,
and the policies let a user reach only their own. The samples sign in
anonymously, so each install is a user of its own and nobody types an email address or a
password.

## Create a project

1. In the [Supabase dashboard](https://supabase.com/dashboard), choose **New project**.
2. Pick an organization, name the project, set a database password and pick a region. Keep the
   password: the command-line route below may ask for it.
3. Under **Security**, leave **Enable Data API** on: the samples read and write through it. The
   other two options there, **Automatically expose new tables** and **Enable automatic RLS**, can
   be either way. The schema grants what it needs and enables row-level security itself.
4. Create the project and wait for it to finish setting up.

Use a project of its own for the samples, not one that holds real data.

## Turn on anonymous sign-ins

1. Open **Authentication**, then **Sign In / Providers**.
2. Under **User Signups**, turn on **Allow anonymous sign-ins**.
3. Choose **Save changes**.

An anonymous user signs in with the `authenticated` role, like any other user, so the schema's
policies treat them the same way. Supabase recommends a CAPTCHA for anonymous sign-ins on a
production project. The samples do not send a CAPTCHA token, so leave it off for this one; Supabase
limits anonymous sign-ins to 30 an hour from each IP address by default.

## Apply the schema

Either route applies the same two files, and both can be repeated: a second run changes nothing.
So the two also mix: a `db push` after the SQL Editor runs the files again, and nothing changes.

### In the SQL Editor

1. Open the **SQL Editor** and start a new query.
2. Paste the whole of
   [`samples/supabase/migrations/20260930000000_offline_tasks.sql`](#the-schema) and choose
   **Run**.
3. The editor warns that the query "includes destructive operations". The file drops and recreates
   its own policies so that it can run twice, and drops nothing else. Choose **Run query**.
4. Start a new query, paste the whole of
   `samples/supabase/migrations/20261011000000_functions_realtime_and_photo_updates.sql`, and run it
   the same way.

Each finishes with "Success. No rows returned".

### With the Supabase CLI

With the [Supabase CLI](https://supabase.com/docs/guides/local-development/cli/getting-started)
installed, from the repository root:

```bash
supabase login      # once: opens the browser to sign the CLI in
cd samples
supabase link       # pick the project; it may ask for the database password
supabase db push    # lists the migrations not yet applied and asks before applying them
```

`samples/` is a small CLI project, and the two schema files are its migrations.
`supabase link --project-ref <ref>` skips the picker, and `-p <password>` answers the password
prompt. The project ref is the part of the project URL before `.supabase.co`.

### If it stops

Before it changes anything, the file checks for three things it would otherwise keep, and stops
with a message and a hint if it finds one:

- a `tasks` or `task_photos` table in another shape;
- the `moddatetime` extension in a schema other than `extensions`;
- another policy on `storage.objects` that names the `task-photos` bucket. Policies add up, so one
  left behind would open the bucket to whoever it admits.

A project that once ran this repository's old demo schema has all three: its tables, the
extension in `public`, and four policies that let the publishable key alone read, upload and
delete every file in `task-photos`. Deal with each as the hint says, then run the file again.

## What it creates

| Object | What it is |
| --- | --- |
| `public.tasks` | `id`, `user_id`, `title`, `notes`, `done`, `created_at`, `updated_at` |
| `public.task_photos` | `id`, `user_id`, `task_id`, `object_path`, `width`, `height`, `created_at`, `updated_at` |
| Row-level security | Enabled and forced on both tables. Each has four policies, one per operation, and each admits a row only when its `user_id` is the caller's id |
| Grants | Nothing for `anon`. `select`, `insert`, `update` and `delete` for `authenticated`, and for `service_role`, which the samples never use |
| `updated_at` | Set by the server on every update, with the `moddatetime` extension, installed in the `extensions` schema |
| Storage bucket `task-photos` | Private, JPEG only, 5 MB per file |
| Storage policies | Read, upload, update and delete, each only under `<user id>/` in `task-photos`. Update is the second file's: Storage's replace and move need it, and copy needs read and upload |
| `public.complete_all_tasks()` | Marks every open task of the caller done, and returns how many it changed. The samples' **Mark all done** |
| `public.tasks_done_count(created_since)` | How many of the caller's tasks are done, those created since `created_since` when it is given. The samples' **Server** section |
| Functions' grants | `execute` for `authenticated` and `service_role` only. Both run as the caller (`security invoker`), so the tables' policies decide which rows they reach |
| Realtime | `tasks` and `task_photos` in the `supabase_realtime` publication, so the samples' **Live updates** get their changes. Realtime sends a user only the rows the policies let them read |

The rules the sample apps build on:

- **The app makes every id.** `tasks.id` and `task_photos.id` have no server default. A task
  created offline can be opened, edited and given photos before it syncs, and a replayed insert
  carries the same id each time. An insert without an id is refused. So is a row added in the
  Table Editor without an `id` and a `user_id`: the editor has no signed-in user to fill
  `user_id` in.
- **One rule refuses a write on purpose.** A title must not be blank (spaces, tabs and line breaks
  only) and must be at most 200 characters. The samples queue a write that breaks it to show what
  a rejected write looks like when the queue replays. Two more rules would refuse a write by
  accident: `notes` is at most 2,000 characters, and `width` and `height` are positive or null,
  never 0.
- **A photo is one JPEG, at one path.** Its file lives at exactly
  `<user id>/<task id>/<photo id>.jpg`, the ids in lower case as Postgres writes them, and the row
  may name no other file. Swift's `uuidString` is upper case, so an iOS app lower-cases it first
  (`uuidString.lowercased()`), or Storage and the row both refuse it. The samples re-encode every
  photo to a JPEG under 5 MB and upload it as `image/jpeg`; the bucket refuses anything else.
- **A photo belongs to its owner's task.** A photo row names its task and its owner together, so
  no one can attach a photo to someone else's task.
- **Deleting stops at the database.** Deleting a task deletes its photo rows, and deleting a user
  deletes their rows in both tables, but neither deletes files: Storage is a separate service, and
  only the app deletes a file, through the Storage API. So the samples delete a task in this
  order: its files (online only), then its photo rows, then the task. A task deleted offline
  leaves its files behind, because nothing queues a Storage delete. Supabase also refuses to
  delete a user who still owns files
  ([Managing user data](https://supabase.com/docs/guides/auth/managing-user-data)): delete their
  files first.

## Check it

- **Table Editor** lists `tasks` and `task_photos`, and neither carries the red **Unrestricted**
  badge that marks a table without row-level security.
- **Authentication**, then **Policies**, shows four policies on each table.
- **Storage**, then **Files**, lists `task-photos` with no **Public** badge, a 5 MB file size
  limit, `image/jpeg` as its only type, and **4** in its **Policies** column: the two files' four,
  each for signed-in users only. A higher number means another policy also reaches the bucket.
- **Database**, then **Functions**, lists `complete_all_tasks` and `tasks_done_count`.
- **Database**, then **Publications**, shows `tasks` and `task_photos` under
  `supabase_realtime`.

## Find the URL and the publishable key

Choose **Connect** at the top of the project's dashboard: it shows the project URL
(`https://<ref>.supabase.co`) and the publishable key. The keys are also under **Project
Settings**, then **API Keys**.

Use the **publishable** key (`sb_publishable_…`), which is made to ship inside an app. Never put
the secret key (`sb_secret_…`), or the legacy `service_role` key, in an app: it skips row-level
security. The legacy `anon` key also works, until Supabase retires it (planned for the end of
2026).

## Where the URL and the key go

When each sample lands, it reads the two values from a file that git ignores, so they never reach
a commit:

| Sample | File | Entries |
| --- | --- | --- |
| Android | `local.properties`, at the repository root | `SUPABASE_URL=https://<ref>.supabase.co`<br>`SUPABASE_PUBLISHABLE_KEY=sb_publishable_…` |
| iOS | `samples/ios/Configuration/Secrets.xcconfig` | `SUPABASE_URL = <ref>.supabase.co`<br>`SUPABASE_PUBLISHABLE_KEY = sb_publishable_…` |

The iOS URL has no `https://`, because `//` starts a comment in an xcconfig file. The pages for
[the Android sample](android.md) and [the iOS sample](ios.md) take it from there.

## What the key can reach

The publishable key is public: anyone can take it out of an app. With this schema:

- A request with the key and no signed-in user reaches nothing: no table grants anything to
  `anon`, and no Storage policy admits it.
- A signed-in user, anonymous or not, reaches only the rows and files that carry their own id.
  Knowing another user's ids does not help: a read returns nothing, and an update or a delete
  changes nothing.
- A file is a JPEG of at most 5 MB, and none has a public URL.

An anonymous user cannot sign back in: after signing out, or deleting the app, their tasks stay on
the server but the app can never reach them again. Two devices are two users, so the
[guided tour](tour.md) plays the second writer from the Table Editor instead.

## If the project pauses

Supabase pauses a free project after a week with little activity, and emails the owner about a
week before it does. The data stays, but the samples cannot reach the project until it is
resumed: open it in the dashboard and choose **Resume project**. A paused project can be resumed
for up to a year. A few requests to the database each day, from the samples or the dashboard,
keep it from pausing. See
[Project pausing](https://supabase.com/docs/guides/platform/free-project-pausing).

## When the schema changes

The first file is the samples' first migration, and what it creates is never changed once
released; it only learns the names of the later files' own Storage policies, so its check for
other policies on the bucket lets it run again after them. A later change to the schema is a new
file in `samples/supabase/migrations/`, named for the time it was written so it sorts after the
others, as the second file is. `supabase db push` applies each file once, in order, and never
runs one it has applied again. In the SQL Editor, run each new file once, in the same order. Either
file can still be run again at any time and changes nothing.

## Cleaning up files and users

Two things pile up in a project the samples have used for a while, because nothing on the device
can remove them:

- **Files with no row.** A task deleted offline keeps its photos' files: Storage writes are never
  queued, so the files stay in the bucket after the rows go.
- **Anonymous users nobody can reach.** An anonymous user who signs out, or deletes the app, can
  never sign back in, and their tasks, photos and files stay behind.

The samples never hold the secret key, so they cannot clean up after themselves. Sweep by hand
from the dashboard now and then, or, for a project that wants it done on a schedule, write an Edge
Function that does the same with the secret key.

**Files with no row.** List them in the SQL Editor:

```sql
select o.name
from storage.objects o
where o.bucket_id = 'task-photos'
  and not exists (select 1 from public.task_photos p where p.object_path = o.name);
```

Then delete those files in **Storage**, under `task-photos`: Supabase does not let SQL delete
stored files.

**Anonymous users.** Files first: Supabase refuses to delete a user who still owns files. List the
files of anonymous users who have not signed in for 30 days:

```sql
select o.name
from storage.objects o
join auth.users u on split_part(o.name, '/', 1) = u.id::text
where o.bucket_id = 'task-photos'
  and u.is_anonymous
  and u.last_sign_in_at < now() - interval '30 days';
```

Delete those files in **Storage**, then the users. Their tasks and photo rows go with them:

```sql
delete from auth.users
where is_anonymous
  and last_sign_in_at < now() - interval '30 days';
```

## Remove it all

The end of each file has a commented-out "how to reset" section. Run the second file's first. Then
delete the `task-photos` bucket in **Storage** (Supabase does not let SQL delete stored files), and
run the first file's `drop` statements. That removes everything the files created, and running
them again starts from empty.

## The schema

Included from the repository when the site is built:

??? example "samples/supabase/migrations/20260930000000_offline_tasks.sql"

    ```sql
    --8<-- "samples/supabase/migrations/20260930000000_offline_tasks.sql"
    ```

??? example "samples/supabase/migrations/20261011000000_functions_realtime_and_photo_updates.sql"

    ```sql
    --8<-- "samples/supabase/migrations/20261011000000_functions_realtime_and_photo_updates.sql"
    ```
