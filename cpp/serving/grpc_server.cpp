//
// Copyright 2022 DMetaSoul
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//     http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.
//

#include <common/logger.h>
#include <serving/converters.h>
#include <serving/grpc_server.h>
#include <serving/metaspore.grpc.pb.h>
#include <serving/model_manager.h>
#include <serving/types.h>
#include <serving/shared_grpc_server_builder.h>
#include <serving/shared_grpc_context.h>
#include <metaspore/string_utils.h>

#include <agrpc/asioGrpc.hpp>
#include <boost/asio/bind_executor.hpp>
#include <boost/asio/signal_set.hpp>
#include <fmt/format.h>
#include <gflags/gflags.h>
#include <grpcpp/server.h>
#include <grpcpp/server_builder.h>

#include <optional>

namespace metaspore::serving {

DECLARE_string(grpc_listen_host);
DECLARE_string(grpc_listen_port);

// From
// https://github.com/Tradias/asio-grpc/blob/496408239c4cb62e9360088aba8c74b2ec3a04e7/example/multi-threaded-server.cpp#L29
// Copyright 2022 Dennis Hezel
struct ServerShutdown {
    grpc::Server &server;
    boost::asio::basic_signal_set<agrpc::GrpcContext::executor_type> signals;
    std::atomic_bool is_shutdown{};
    std::thread shutdown_thread;

    ServerShutdown(grpc::Server &server, agrpc::GrpcContext &grpc_context)
        : server(server), signals(grpc_context, SIGINT, SIGTERM) {
        signals.async_wait([&](auto &&ec, auto &&signal) {
            if (boost::asio::error::operation_aborted != ec) {
                spdlog::info("Shutdown with signal {}", signal);
                shutdown();
            }
        });
    }

    void shutdown() {
        if (!is_shutdown.exchange(true)) {
            // We cannot call server.Shutdown() on the same thread that runs a GrpcContext
            // because that could lead to deadlock, therefore create a new thread.
            shutdown_thread = std::thread([&] {
                signals.cancel();
                server.Shutdown();
            });
        }
    }

    ~ServerShutdown() {
        if (shutdown_thread.joinable()) {
            shutdown_thread.join();
        } else if (!is_shutdown.exchange(true)) {
            server.Shutdown();
        }
    }
};

class GrpcServerContext {
  public:
    GrpcServerContext()
            : builder(SharedGrpcServerBuilder::get_instance())
            , predict_service()
            , load_service()
            , grpc_context(SharedGrpcContext::get_instance()) {
        spdlog::info("Listening on {}:{}", FLAGS_grpc_listen_host, FLAGS_grpc_listen_port);
        builder->AddListeningPort(
            fmt::format("{}:{}", FLAGS_grpc_listen_host, FLAGS_grpc_listen_port),
            grpc::InsecureServerCredentials());
        builder->RegisterService(&predict_service);
        builder->RegisterService(&load_service);
        server = builder->BuildAndStart();
    }

    std::shared_ptr<grpc::ServerBuilder> builder;
    Predict::AsyncService predict_service;
    Load::AsyncService load_service;
    std::shared_ptr<agrpc::GrpcContext> grpc_context;
    std::unique_ptr<grpc::Server> server;
};

GrpcServer::GrpcServer() { context_ = std::make_unique<GrpcServerContext>(); }

GrpcServer::~GrpcServer() = default;

GrpcServer::GrpcServer(GrpcServer &&) = default;

// From
// https://github.com/Tradias/asio-grpc/blob/f179621e3ff5401b99e4c40ba2427a1a1ab7ffcf/example/helper/coSpawner.hpp
// Copyright 2022 Dennis Hezel
template <class Handler> struct CoSpawner {
    using executor_type = boost::asio::associated_executor_t<Handler>;
    using allocator_type = boost::asio::associated_allocator_t<Handler>;

    Handler handler;

    explicit CoSpawner(Handler handler) : handler(std::move(handler)) {}

    template <class T> void operator()(agrpc::RepeatedlyRequestContext<T> &&request_context) {
        boost::asio::co_spawn(
            this->get_executor(),
            [handler = std::move(handler), request_context = std::move(request_context)]() mutable
            -> boost::asio::awaitable<void> {
                co_await std::apply(std::move(handler), request_context.args());
            },
            boost::asio::detached);
    }

    [[nodiscard]] executor_type get_executor() const noexcept {
        return boost::asio::get_associated_executor(handler);
    }

    [[nodiscard]] allocator_type get_allocator() const noexcept {
        return boost::asio::get_associated_allocator(handler);
    }
};

awaitable<void> respond_error(grpc::ServerAsyncResponseWriter<PredictReply> &writer,
                              const status &s) {
    co_await agrpc::finish_with_error(
        writer, grpc::Status(static_cast<grpc::StatusCode>(s.code()), s.ToString()),
        boost::asio::use_awaitable);
}

awaitable<void> respond_error(grpc::ServerAsyncResponseWriter<LoadReply> &writer,
                              const status &s) {
    co_await agrpc::finish_with_error(
        writer, grpc::Status(static_cast<grpc::StatusCode>(s.code()), s.ToString()),
        boost::asio::use_awaitable);
}

void GrpcServer::run() {
    ServerShutdown server_shutdown{*context_->server, *context_->grpc_context};

    agrpc::repeatedly_request(
        &Predict::AsyncService::RequestPredict, context_->predict_service,
        CoSpawner{boost::asio::bind_executor(
            *context_->grpc_context,
            [&](grpc::ServerContext &ctx, PredictRequest &req,
                grpc::ServerAsyncResponseWriter<PredictReply> writer) -> awaitable<void> {
                auto find_model = ModelManager::get_model_manager().get_model(req.model_name());
                if (!find_model.ok()) {
                    co_await respond_error(writer, find_model.status());
                } else {
                    // convert grpc to fe input
                    std::string ex;
                    try {
                        auto reply_result = co_await(*find_model)->predict(req);
                        if (!reply_result.ok()) {
                            co_await respond_error(writer, reply_result.status());
                        } else {
                            co_await agrpc::finish(writer, *reply_result, grpc::Status::OK,
                                                   boost::asio::use_awaitable);
                        }
                    } catch (const std::exception &e) {
                        // unknown exception
                        ex = e.what();
                    }
                    if (!ex.empty())
                        co_await respond_error(writer, absl::UnknownError(std::move(ex)));
                }
                co_return;
            })});

    agrpc::repeatedly_request(
        &Load::AsyncService::RequestLoad, context_->load_service,
        CoSpawner{boost::asio::bind_executor(
            *context_->grpc_context,
            [&](grpc::ServerContext &ctx, LoadRequest &req,
                grpc::ServerAsyncResponseWriter<LoadReply> writer) -> awaitable<void> {
                const std::string &model_name = req.model_name();
                const std::string &version = req.version();
                const std::string &dir_path = req.dir_path();
                std::string desc = " model " + metaspore::ToSource(model_name) +
                                   " version " + metaspore::ToSource(version) +
                                   " from " + metaspore::ToSource(dir_path) + ".";
                spdlog::info("Loading" + desc);
                auto status = co_await ModelManager::get_model_manager().load(dir_path, model_name);
                if (!status.ok()) {
                    spdlog::error("Fail to load" + desc);
                    co_await respond_error(writer, status);
                } else {
                    LoadReply reply;
                    reply.set_msg("Successfully loaded" + desc);
                    spdlog::info(reply.msg());
                    co_await agrpc::finish(writer, reply, grpc::Status::OK,
                                           boost::asio::use_awaitable);
                }
                co_return;
            })});

    spdlog::info("Start to accept grpc requests");
    context_->grpc_context->run();
}

} // namespace metaspore::serving
