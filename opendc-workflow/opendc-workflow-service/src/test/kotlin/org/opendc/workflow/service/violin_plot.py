import matplotlib.pyplot as plt
import matplotlib.patches as mpatches
import numpy as np
import ast

def getData(path):
    data = dict()
    with open(path, 'r') as f:
        for i, line in enumerate(f.readlines()):
            content = line.split(';')
            if i != 0 and len(content) == 4:
                reader = content[0].strip()
                metric = content[1].strip()
                value = content[2].strip()
                unit = content[3].replace('\n', '').strip()
                if value[0] == '[' and value[-1] == ']':
                    value = ast.literal_eval(value)
                data[metric] = [value, unit]
    return data

def getTrace(path):
    trace = dict()
    with open(path, 'r') as f:
        with open(path, 'r') as f:
            for i, line in enumerate(f.readlines()):
                content = line.split(',')
                if i == 0:
                    for category in [i.strip() for i in content]:
                        trace[category.strip()] = []
                elif i != 0:
                    for j in range(len(trace.keys())):
                        if j > len(content):
                            trace[list(trace.keys())[j]].append(np.nan)
                        else:
                            trace[list(trace.keys())[j]].append(content[j].strip())
        return trace

if __name__ == "__main__":
    data = getData('../../../../../../../results_monitor.csv')
    trace = getTrace('../../../../../resources/trace.gwf')
    data_energy = [np.array(data['energyUsage'][0]).astype(float)]
    data_cpuUtil = [np.array(data['cpuUtilization'][0]).astype(float)]

    ###########################################################################
    # Box and violin plot of different policies over time.                    #
    ###########################################################################

#     data_cpuUtil = [getData('cpu1'), getData('cpu2'), getData('cpu3')]
#     data_energy = [getData('energy1'), getData('energy2'), getData('energy3')]
#
    # Box plot combined with a violin plot of cpu utilization.
#     fig = plt.figure(figsize=(7,4.5))
#     ax = fig.subplots()
#     ax.set_title("Workflow CPU Utilization per policy")
#     ax.set_xlabel("CPU Utilization (%)")
#     ax.set_ylabel("Policy")
#     ax.boxplot([i*100 for i in reversed(data_cpuUtil)], vert=False, showmeans=True, meanline=True, sym='', whis=2, widths=0.3)
#     ax.violinplot([i*100 for i in reversed(data_cpuUtil)], vert=False, showmeans=True, widths=0.7)
#     ax.set_yticklabels(reversed(['Def', 'SJO', 'RTE'])) # 'Default', 'SizeJobOrderPolicy', 'RandomTaskEligibilityPolicy'
# #     ax.set_xlim(-1,101)
#     plt.tight_layout()
#     plt.savefig('cpuUtil.png', transparent=True)
#     plt.show()

#     # Box plot combined with a violin plot of energy usage.
#     fig = plt.figure(figsize=(7,4.5))
#     ax = fig.subplots()
#     ax.set_title("Workflow energy usage per policy")
#     ax.set_xlabel("Energy usage (Wh)")
#     ax.set_ylabel("Policy")
#     ax.boxplot([i/3600 for i in reversed(data_energy)], vert=False, showmeans=True, meanline=True, sym='', whis=2, widths=0.3)
#     ax.violinplot([i/3600 for i in reversed(data_energy)], vert=False, showmeans=True, widths=0.7)
#     ax.set_yticklabels(reversed(['Def', 'SJO', 'RTE'])) # 'Default', 'SizeJobOrderPolicy', 'RandomTaskEligibilityPolicy'
#     ax.set_xlim(np.min([np.min(i/3600) for i in data_energy])-2, np.max([np.max(i/3600) for i in data_energy])+1)
# #     ax.plot([], [], ' ', label="Def = Default policy")
# #     ax.plot([], [], ' ', label="SJO = Size Job Order Policy")
# #     ax.plot([], [], ' ', label="RTE = Random Task Eligibility Policy")
# #     plt.legend()
#     plt.tight_layout()
#     plt.savefig('energyUse.png', transparent=True)
#     plt.show()

    ###########################################################################
    # Staircase plot of active jobs over time.                                #
    ###########################################################################

    data_active_jobs = [np.array(data['guestsRunning'][0]).astype(float)]
    def staircase_average(a, n=3):
        steps = int(len(a)/n)
        steps_remainder = int(len(a)%n)
        staircase = np.zeros(len(a))
        for i in range(steps):
            staircase[i*n:(1+i)*n] = np.average(a[i*n:(1+i)*n])
        staircase[steps:steps_remainder] = np.average(a[steps:steps_remainder])
        return staircase

    fig = plt.figure(figsize=(12,4))
    ax = fig.subplots()
    ax.set_title("Guests running over time")
    ax.plot(data_active_jobs[0], color='green', alpha=0.1, zorder=10)
    staircase_avg = staircase_average(data_active_jobs[0], 10)
    ax.plot(staircase_avg, color='green', alpha=0.85, zorder=10)
    ax.set_xlabel("Scheduler Cycles")
    ax.set_ylabel("Active jobs")
    ax.set_ylim(bottom=0, top = (max(staircase_avg) + max(staircase_avg)*0.1))
    plt.tight_layout()
    plt.savefig('active_jobs.png', transparent=True)
    plt.show()


    ###########################################################################
    # Line plot of energy usage compared to cpu utilization over time.        #
    ###########################################################################

    def moving_average(a, n=3):
        ret = np.cumsum(a, dtype=float)
        ret[n:] = ret[n:] - ret[:-n]
        return ret[n - 1:] / n
    fig = plt.figure(figsize=(12,4))
    ax = fig.subplots()
    ax.set_title("Workflow energy usage compared to CPU utilization over time")
    ax.plot(data_energy[0]/3600, color='red', alpha=0.1, zorder=10)
    ax.plot(moving_average(data_energy[0]/3600, 15), color='red', alpha=0.85, zorder=10)
    ax.set_xlabel("Scheduler Cycles")
    ax.set_ylabel("Energy usage (Wh)", color='red')
    ax.set_ylim(bottom=0)
    ax.hlines(np.max(data_energy[0]/3600), -10, len(data_cpuUtil[0])+10, linestyles='--', color='grey', alpha=0.7, zorder=0)
    ax.hlines(np.min(data_energy[0]/3600), -10, len(data_cpuUtil[0])+10, linestyles='--', color='grey', alpha=0.7, zorder=0)
    ax2=ax.twinx()
    ax2.plot(data_cpuUtil[0]*100, color='blue', alpha=0.1, zorder=5)
    ax2.plot(moving_average(data_cpuUtil[0]*100, 15), color='blue', alpha=0.85, zorder=5)
    ax2.set_ylabel("CPU Utilization (%)", color='blue')
    ax2.set_ylim(0,101)
    plt.xlim(-10, len(data_cpuUtil[0])+10)
    plt.tight_layout()
    plt.savefig('twinEnergyUtilization.png', transparent=True)
    plt.show()


    ###########################################################################
    # Previous line plot and violin plots combined in a subplot.              #
    ###########################################################################

    # Line plot of energy usage compared to cpu utilization over time.
    fig = plt.figure(figsize=(15,5))
    ax = fig.subplots(2,3, gridspec_kw={'width_ratios': [10, 1, 1], 'height_ratios': [2, 1], 'hspace':0.0})

    def moving_average(a, n=3):
        ret = np.cumsum(a, dtype=float)
        ret[n:] = ret[n:] - ret[:-n]
        return ret[n - 1:] / n
    ax[0][0].set_title("Workflow energy usage compared to CPU utilization over time\nRolling average of 15")
    ax[0][0].plot(data_energy[0]/3600, color='red', alpha=0.1, zorder=10)
    ax[0][0].plot(moving_average(data_energy[0]/3600, 15), color='red', alpha=0.85, zorder=10)
    ax[0][0].set_ylabel("Energy usage (Wh)", color='red')
    ax[0][0].set_ylim(bottom=0)
    ax[0][0].hlines(np.max(data_energy[0]/3600), -10, len(data_cpuUtil[0])+10, linestyles='--', color='grey', alpha=0.7, zorder=0)
    ax[0][0].hlines(np.min(data_energy[0]/3600), -10, len(data_cpuUtil[0])+10, linestyles='--', color='grey', alpha=0.7, zorder=0)
    ax002=ax[0][0].twinx()
    ax002.plot(data_cpuUtil[0]*100, color='blue', alpha=0.1, zorder=5)
    ax002.plot(moving_average(data_cpuUtil[0]*100, 15), color='blue', alpha=0.85, zorder=5)
    ax002.set_ylabel("CPU Utilization (%)", color='blue')
    ax002.set_ylim(0,100)
    ax[0][0].set_xlim(-10, len(data_cpuUtil[0])+10)
    ax[0][0].set_xticklabels([])
    ax[0][0].set_xticks([])
    ax[0][0].set_xticks([], minor=True)

    # Box plot combined with a violin plot of energy usage.
    ax[0][1].set_title("Energy usage\ndistribution")
    ax[0][1].set_ylabel("Energy usage (Wh)", color='red')
    ax[0][1].set_xlabel("Policy")
    ax[0][1].boxplot(data_energy[0]/3600, vert=True, showmeans=True, meanline=True, sym='', whis=1.5)
    violin_parts  = ax[0][1].violinplot(data_energy[0]/3600, vert=True, showmeans=True)
    for pc in violin_parts['bodies']:
        pc.set_facecolor('red')
        pc.set_edgecolor('black')
#         pc.set_alpha(1)
    ax[0][1].set_ylim(bottom=0)
    ax[0][1].set_xticklabels([])

   # Box plot combined with a violin plot of cpu utilization.
    ax[0][2].set_title("CPU utilization\ndistribution")
    ax[0][2].set_ylabel("CPU Utilization (%)", color='blue')
    ax[0][2].set_xlabel("Policy")
    ax[0][2].boxplot(data_cpuUtil[0]*100, vert=True, showmeans=True, meanline=True, sym='', whis=1.5)
    violin_parts  = ax[0][2].violinplot(data_cpuUtil[0]*100, vert=True, showmeans=True)
    for pc in violin_parts['bodies']:
        pc.set_facecolor('blue')
        pc.set_edgecolor('black')
#         pc.set_alpha(1)
    ax[0][2].set_xticklabels([])
    ax[0][2].set_ylim(0,100)

    # Staircase plot showing the average active jobs over time
    data_active_jobs = [np.array(data['guestsRunning'][0]).astype(float)]
    def staircase_average(a, n=15):
        steps = int(len(a)/n)
        steps_remainder = int(len(a)%n)
        staircase = np.zeros(len(a))
        for i in range(steps):
            staircase[i*n:(1+i)*n] = np.average(a[i*n:(1+i)*n])
        staircase[steps:steps_remainder] = np.average(a[steps:steps_remainder])
        return staircase

    ax[1][0].plot(data_active_jobs[0], color='green', alpha=0.1, zorder=10)
    staircase_avg = staircase_average(data_active_jobs[0], 25)
    ax[1][0].plot(staircase_avg, color='green', alpha=1, zorder=10)
    ax[1][0].fill_between([i for i in range(len(staircase_avg))], staircase_avg, [0 for i in range(len(staircase_avg))], color='lightgreen', alpha=0.75, zorder=10, edgecolor='green')
    ax[1][0].set_xlabel("Scheduler Cycles")
    ax[1][0].set_ylabel("Active jobs", color='green')
    ax[1][0].set_ylim(bottom=0, top = (max(staircase_avg) + max(staircase_avg)*0.1))
    ax[1][0].set_xlabel("Scheduler Cycles")
    ax[1][0].set_xlim(-10, len(data_cpuUtil[0])+10)
    labels = [str(int(i)) for i in ax[1][0].get_yticks()]
    # labels[-1] = labels[-2] = ''
    ax[1][0].set_yticklabels(labels)

    ax[1][1].set_visible(False)
    ax[1][2].set_visible(False)

    plt.tight_layout()
    plt.savefig('combined.png', transparent=True)
    plt.show()


    ###########################################################################
    # Plot trace information.                                                 #
    ###########################################################################

    data2 = getData('../../../../../../../results_monitor2.csv')
    data_energy2 = [np.array(data2['energyUsage'][0]).astype(float)]
    data_cpuUtil2 = [np.array(data2['cpuUtilization'][0]).astype(float)]
    data3 = getData('../../../../../../../results_monitor3.csv')
    data_energy3 = [np.array(data3['energyUsage'][0]).astype(float)]
    data_cpuUtil3 = [np.array(data3['cpuUtilization'][0]).astype(float)]

    data_energy_ls = [data_energy[0]/3600, data_energy3[0]/3600, data_energy2[0]/3600]
    data_cpuUtil_ls = [data_cpuUtil[0]*100, data_cpuUtil3[0]*100, data_cpuUtil2[0]*100]

    fig = plt.figure(figsize=(15,4.5))
    plt.rcParams.update({'font.size': 14})
    ax = fig.subplots(1, 2)

    # Box plot combined with a violin plot of energy usage.
    ax[0].set_title("Energy usage per policy")
    ax[0].set_xlabel("Energy usage (Wh)")
    ax[0].boxplot(data_energy_ls, vert=False, showmeans=True, meanline=True, sym='', whis=2, widths=0.3)
    violin_parts = ax[0].violinplot(data_energy_ls, vert=False, showmeans=True, widths=0.8)
    for pc in violin_parts['bodies']:
        pc.set_facecolor('red')
        pc.set_edgecolor('black')
    ax[0].set_yticklabels(reversed(['Filter', 'Random', 'TaskFlow']))

    # Box plot combined with a violin plot of cpu utilization.
    ax[1].set_title("CPU Utilization per policy")
    ax[1].set_xlabel("CPU Utilization (%)")
    ax[1].boxplot(data_cpuUtil_ls, vert=False, showmeans=True, meanline=True, sym='', whis=2, widths=0.3)
    violin_parts = ax[1].violinplot(data_cpuUtil_ls, vert=False, showmeans=True, widths=0.8)
    for pc in violin_parts['bodies']:
        pc.set_facecolor('blue')
        pc.set_edgecolor('black')
    ax[1].set_yticklabels(reversed(['Filter', 'Random', 'TaskFlow']))

    plt.subplots_adjust(left=0.1,
                        bottom=0.140,
                        right=0.9,
                        top=0.9,
                        wspace=0.4,
                        hspace=0.4)
    plt.savefig('energy_cpu_dist.png', transparent=True)
    plt.show()

    ###########################################################################
    # Plot trace information.                                                 #
    ###########################################################################
    #
    # # Process trace data.
    # workflow_id = np.array(trace['WorkflowID']).astype(int)
    # job_id = np.array(trace['JobID']).astype(int)
    # submit_time = np.array(trace['SubmitTime']).astype(int)
    # run_time = np.array(trace['RunTime']).astype(int)
    # n_procs = np.array(trace['NProcs']).astype(int)
    # req_n_procs = np.array(trace['ReqNProcs']).astype(int)
    # dependencies = np.array([np.array(entry.split(' ')).astype(int) if entry != '' else np.array([]) for entry in trace['Dependencies']], dtype=object)
    #
    # # Compute the active jobs at each timestep.
    # def compute_active_jobs(job_id, workflow_id, submit_time, run_time, dependencies):
    #     print("Computing active jobs per epoch from trace...")
    #     jobs_over_time = dict()
    #     active_jobs = set()
    #     waiting_jobs = set()
    #     finished_jobs = []
    #
    #     # Sort input data by submit time.
    #     sorted_indices = np.argsort(submit_time)
    #     job_id = job_id[sorted_indices]
    #     workflow_id = dict(zip(job_id, workflow_id[sorted_indices]))
    #     submit_time = dict(zip(job_id, submit_time[sorted_indices]))
    #     run_time = dict(zip(job_id, run_time[sorted_indices]))
    #     dependencies = dict(zip(job_id, dependencies[sorted_indices]))
    #
    #     # Iterate over all timesteps.
    #     time = 0
    #     waiting_jobs = list(job_id)
    #     while len(finished_jobs) != len(job_id):
    #         # Add the active jobs to the list.
    #         jobs_over_time[time] = list(active_jobs)
    #
    #         # Check if any jobs have become active.
    #         for job in waiting_jobs:
    #             # Check if the job's submit time has arrived.
    #             if submit_time[job] <= time:
    #                 # Check if the job's dependencies have finished.
    #                 if len(dependencies[job]) == 0 or np.all(np.isin(dependencies[job], finished_jobs)):
    #                     waiting_jobs.remove(job)
    #                     active_jobs.add(job)
    #
    #         # Check if any jobs have finished.
    #         for job in active_jobs.copy():
    #             if run_time[job] == 0:
    #                 active_jobs.remove(job)
    #                 finished_jobs.append(job)
    #             else:
    #                 run_time[job] -= 1
    #
    #         time += 1
    #
    #         print(round(len(finished_jobs)/len(job_id)*100,2), '%', end='\r')
    #
    #     return jobs_over_time
    #
    # jobs_over_time = compute_active_jobs(job_id, workflow_id, submit_time, run_time, dependencies)
    #
    # # Plot trace data.
    # fig = plt.figure(figsize=(12.5,4))
    # ax = fig.subplots()
    # ax.set_title("Active jobs over time")
    # ax.set_xlabel("Time (s)")
    # ax.set_ylabel("Number of active jobs")
    # ax.set_xlim(0, max(jobs_over_time.keys()))
    # ax.set_ylim(0, max([len(jobs) for jobs in jobs_over_time.values()]))
    # for time, jobs in jobs_over_time.items():
    #     ax.scatter(time, len(jobs), color='black', s=1)
    # plt.tight_layout()
    # plt.savefig('trace.png', transparent=True)
    # plt.show()
