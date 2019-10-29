# Plan-Net Reference Server

## Updating the server

1. Usually, you will want to remove all the data from the server with `rm -rf
   data` so that the server starts with a blank slate. Skip this step if you
   want to retain the existing data.
1. Run `docker-compose up` to start the server. If you need to build the docker
   image, run `./build-docker-image.sh` first.
1. In a separate terminal, run `ruby upload.rb` to upload data to the server.
   The IG resources in the `conformance` folder are uploaded along with sample
   data. The sample data is loaded from `../plan-net-resources/output`. You can
   upload the paths in `upload.rb` if you need to load data from somewhere else.
   Every 100 resources, the upload script prints the number of resources that
   have been uploaded.
1. Once the upload has completed, use `CTRL+c` or run `docker-compose down` to
   stop the server.
1. Stage the new data with `git add data`.
1. Commit the data with `git commit -m 'update data'`.
1. Now it is necessary to copy this data into the master branch. First, copy the
   data to a new folder that isn't tracked by git: `cp -R data data2`.
1. Change to the `master` branch: `git checkout master`.
1. Remove the old data `rm -rf data`.
1. Move the new data to the correct location `mv data2 data`.
1. Stage the new data with `git add data`.
1. Commit the data with `git commit -m 'update data'`.
1. Push up your changes with `git push`. The server will automatically redeploy.
