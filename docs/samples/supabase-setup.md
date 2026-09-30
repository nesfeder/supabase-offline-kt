# Set up Supabase

Creating a Supabase project for the sample apps and loading their schema into it. A free project
is enough.

The schema is one file, [`samples/supabase/schema.sql`](#the-schema). It creates two tables,
`tasks` and `task_photos`, and a private Storage bucket, `task-photos`. Every row and every file
belongs to one user, and the policies let a user reach only their own. The samples sign in
anonymously, so each install is a user of its own and nobody types an email address or a
password.

## Create a project

1. In the [Supabase dashboard](https://supabase.com/dashboard), choose **New project**.
2. Pick an organization, name the project, set a database password and pick a region. Keep the
   password: the command-line route below asks for it.
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

Either route applies the same file, and both can be repeated: a second run changes nothing. So the
two also mix: a `db push` after the SQL Editor runs the file again, and nothing changes.

### In the SQL Editor

1. Open the **SQL Editor** and start a new query.
2. Paste the whole of [`samples/supabase/schema.sql`](#the-schema) and choose **Run**.
3. The editor warns that the query "includes destructive operations". The file drops and recreates
   its own policies so that it can run twice, and drops nothing else. Choose **Run query**.

It finishes with "Success. No rows returned".

### With the Supabase CLI

With the [Supabase CLI](https://supabase.com/docs/guides/local-development/cli/getting-started)
installed, from the repository root:

```bash
supabase login      # once: opens the browser to sign the CLI in
cd samples
supabase link       # pick the project; it asks for the database password
supabase db push    # lists the one migration and asks before applying it
```

`samples/` is a small CLI project whose only migration, in `samples/supabase/migrations/`, is a
symbolic link to `schema.sql`. On Windows, clone with symbolic links enabled
(`git clone -c core.symlinks=true …`, which may need Developer Mode), or use the SQL Editor.

`supabase link --project-ref <ref>` skips the picker. The project ref is the part of the project
URL before `.supabase.co`.

## What it creates

| Object | What it is |
| --- | --- |
| `public.tasks` | `id`, `user_id`, `title`, `notes`, `done`, `created_at`, `updated_at` |
| `public.task_photos` | `id`, `user_id`, `task_id`, `object_path`, `width`, `height`, `created_at`, `updated_at` |
| Row-level security | Enabled and forced on both tables. Each has four policies, one per operation, and each admits a row only when its `user_id` is the caller's id |
| Grants | Nothing for `anon`. `select`, `insert`, `update` and `delete` for `authenticated` and `service_role` |
| `updated_at` | Set by the server on every update, with the `moddatetime` extension, installed in the `extensions` schema |
| Storage bucket `task-photos` | Private, 5 MB per file, JPEG, PNG, HEIC and WebP only |
| Storage policies | Read, upload and delete, each only under `<user id>/` in `task-photos`. No update, so a file is never overwritten |

A few rules the sample apps build on:

- **The app makes every id.** `tasks.id` and `task_photos.id` have no server default. A task
  created offline can be opened, edited and given photos before it syncs, and a replayed insert
  carries the same id each time. An insert without an id is refused.
- **One rule refuses a write on purpose.** A title must not be blank and must be at most 200
  characters. The samples queue a write that breaks it to show what a rejected write looks like
  when the queue replays.
- **A photo belongs to its owner's task.** A photo row names its task and its owner together, so
  no one can attach a photo to someone else's task. Its file lives at
  `<user id>/<task id>/<photo id>.jpg`, and the row may only point into that folder.
- **Deleting a task deletes its photo rows** on the server. The samples also delete them
  themselves first, because the offline cache does not see a cascade. Deleting a user deletes
  everything they own in both tables.

## Check it

- **Table Editor** lists `tasks` and `task_photos`, and neither carries the red **Unrestricted**
  badge that marks a table without row-level security.
- **Authentication**, then **Policies**, shows four policies on each table.
- **Storage**, then **Files**, lists `task-photos` with no **Public** badge, a 5 MB file size
  limit and the four image types.

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
- A file can be at most 5 MB and must be one of the four image types, and none has a public URL.

An anonymous user cannot sign back in: after signing out, or deleting the app, their tasks stay on
the server but nobody can reach them again. Two devices are two users, so the
[guided tour](tour.md) plays the second writer from the Table Editor instead.

## If the project pauses

Supabase pauses a free project after a week with little activity, and emails the owner about a
week before it does. The data stays, but the samples cannot reach the project until it is
resumed: open it in the dashboard and choose **Resume project**. A paused project can be resumed
for up to a year. A few requests to the database each day, from the samples or the dashboard,
keep it from pausing. See
[Project pausing](https://supabase.com/docs/guides/platform/free-project-pausing).

## Start over

The end of the file has a commented-out "how to reset" section. Delete the `task-photos` bucket in
**Storage** first (Supabase does not let SQL delete stored files), then run the section's `drop`
statements. That removes everything the file created, and running the file again starts from
empty.

## The schema

Included from the repository when the site is built:

??? example "samples/supabase/schema.sql"

    ```sql
    --8<-- "samples/supabase/schema.sql"
    ```
