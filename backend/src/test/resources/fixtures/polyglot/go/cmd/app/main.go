package main

import "github.com/acme/app/internal/store"

func main() {
	srv := store.NewServer(nil)
	srv.Start()
}
